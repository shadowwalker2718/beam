/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.spark.translation.streaming;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.beam.runners.spark.translation.TranslationUtils.rejectSplittable;
import static org.apache.beam.runners.spark.translation.TranslationUtils.rejectStateAndTimers;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.beam.runners.spark.aggregators.NamedAggregators;
import org.apache.beam.runners.spark.aggregators.SparkAggregators;
import org.apache.beam.runners.spark.coders.CoderHelpers;
import org.apache.beam.runners.spark.io.ConsoleIO;
import org.apache.beam.runners.spark.io.CreateStream;
import org.apache.beam.runners.spark.io.SparkUnboundedSource;
import org.apache.beam.runners.spark.stateful.SparkGroupAlsoByWindowViaWindowSet;
import org.apache.beam.runners.spark.metrics.MetricsAccumulator;
import org.apache.beam.runners.spark.metrics.SparkMetricsContainer;
import org.apache.beam.runners.spark.translation.BoundedDataset;
import org.apache.beam.runners.spark.translation.Dataset;
import org.apache.beam.runners.spark.translation.DoFnFunction;
import org.apache.beam.runners.spark.translation.EvaluationContext;
import org.apache.beam.runners.spark.translation.GroupCombineFunctions;
import org.apache.beam.runners.spark.translation.MultiDoFnFunction;
import org.apache.beam.runners.spark.translation.SparkAssignWindowFn;
import org.apache.beam.runners.spark.translation.SparkKeyedCombineFn;
import org.apache.beam.runners.spark.translation.SparkPCollectionView;
import org.apache.beam.runners.spark.translation.SparkPipelineTranslator;
import org.apache.beam.runners.spark.translation.SparkRuntimeContext;
import org.apache.beam.runners.spark.translation.TransformEvaluator;
import org.apache.beam.runners.spark.translation.TranslationUtils;
import org.apache.beam.runners.spark.translation.WindowingHelpers;
import org.apache.beam.runners.spark.util.GlobalWatermarkHolder;
import org.apache.beam.runners.spark.util.SideInputBroadcast;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.CombineWithContext;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.CombineFnUtil;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TaggedPValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.spark.Accumulator;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;


/**
 * Supports translation between a Beam transform, and Spark's operations on DStreams.
 */
final class StreamingTransformTranslator {

  private StreamingTransformTranslator() {
  }

  private static <T> TransformEvaluator<ConsoleIO.Write.Unbound<T>> print() {
    return new TransformEvaluator<ConsoleIO.Write.Unbound<T>>() {
      @Override
      public void evaluate(ConsoleIO.Write.Unbound<T> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        JavaDStream<WindowedValue<T>> dstream =
            ((UnboundedDataset<T>) (context).borrowDataset(transform)).getDStream();
        dstream.map(WindowingHelpers.<T>unwindowFunction()).print(transform.getNum());
      }
    };
  }

  private static <T> TransformEvaluator<Read.Unbounded<T>> readUnbounded() {
    return new TransformEvaluator<Read.Unbounded<T>>() {
      @Override
      public void evaluate(Read.Unbounded<T> transform, EvaluationContext context) {
        context.putDataset(
            transform,
            SparkUnboundedSource.read(
                context.getStreamingContext(),
                context.getRuntimeContext(),
                transform.getSource()));
      }
    };
  }

  private static <T> TransformEvaluator<CreateStream<T>> createFromQueue() {
    return new TransformEvaluator<CreateStream<T>>() {
      @Override
      public void evaluate(CreateStream<T> transform, EvaluationContext context) {
        Coder<T> coder = context.getOutput(transform).getCoder();
        JavaStreamingContext jssc = context.getStreamingContext();
        Queue<Iterable<T>> values = transform.getBatches();
        WindowedValue.ValueOnlyWindowedValueCoder<T> windowCoder =
            WindowedValue.getValueOnlyCoder(coder);
        // create the DStream from queue.
        Queue<JavaRDD<WindowedValue<T>>> rddQueue = new LinkedBlockingQueue<>();
        for (Iterable<T> v : values) {
          Iterable<WindowedValue<T>> windowedValues =
              Iterables.transform(v, WindowingHelpers.<T>windowValueFunction());
          JavaRDD<WindowedValue<T>> rdd =
              jssc.sparkContext()
                  .parallelize(CoderHelpers.toByteArrays(windowedValues, windowCoder))
                  .map(CoderHelpers.fromByteFunction(windowCoder));
          rddQueue.offer(rdd);
        }

        JavaInputDStream<WindowedValue<T>> inputDStream = jssc.queueStream(rddQueue, true);
        UnboundedDataset<T> unboundedDataset = new UnboundedDataset<T>(
            inputDStream, Collections.singletonList(inputDStream.inputDStream().id()));
        // add pre-baked Watermarks for the pre-baked batches.
        Queue<GlobalWatermarkHolder.SparkWatermarks> times = transform.getTimes();
        GlobalWatermarkHolder.addAll(
            ImmutableMap.of(unboundedDataset.getStreamSources().get(0), times));
        context.putDataset(transform, unboundedDataset);
      }
    };
  }

  private static <T> TransformEvaluator<Flatten.FlattenPCollectionList<T>> flattenPColl() {
    return new TransformEvaluator<Flatten.FlattenPCollectionList<T>>() {
      @SuppressWarnings("unchecked")
      @Override
      public void evaluate(Flatten.FlattenPCollectionList<T> transform, EvaluationContext context) {
        List<TaggedPValue> pcs = context.getInputs(transform);
        // since this is a streaming pipeline, at least one of the PCollections to "flatten" are
        // unbounded, meaning it represents a DStream.
        // So we could end up with an unbounded unified DStream.
        final List<JavaDStream<WindowedValue<T>>> dStreams = new ArrayList<>();
        final List<Integer> streamingSources = new ArrayList<>();
        for (TaggedPValue pv : pcs) {
          checkArgument(
              pv.getValue() instanceof PCollection,
              "Flatten had non-PCollection value in input: %s of type %s",
              pv.getValue(),
              pv.getValue().getClass().getSimpleName());
          PCollection<T> pcol = (PCollection<T>) pv.getValue();
          Dataset dataset = context.borrowDataset(pcol);
          if (dataset instanceof UnboundedDataset) {
            UnboundedDataset<T> unboundedDataset = (UnboundedDataset<T>) dataset;
            streamingSources.addAll(unboundedDataset.getStreamSources());
            dStreams.add(unboundedDataset.getDStream());
          } else {
            // create a single RDD stream.
            Queue<JavaRDD<WindowedValue<T>>> q = new LinkedBlockingQueue<>();
            q.offer(((BoundedDataset) dataset).getRDD());
            //TODO: this is not recoverable from checkpoint!
            JavaDStream<WindowedValue<T>> dStream = context.getStreamingContext().queueStream(q);
            dStreams.add(dStream);
          }
        }
        // start by unifying streams into a single stream.
        JavaDStream<WindowedValue<T>> unifiedStreams =
            context.getStreamingContext().union(dStreams.remove(0), dStreams);
        context.putDataset(transform, new UnboundedDataset<>(unifiedStreams, streamingSources));
      }
    };
  }

  private static <T, W extends BoundedWindow> TransformEvaluator<Window.Bound<T>> window() {
    return new TransformEvaluator<Window.Bound<T>>() {
      @Override
      public void evaluate(final Window.Bound<T> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        UnboundedDataset<T> unboundedDataset =
            ((UnboundedDataset<T>) context.borrowDataset(transform));
        JavaDStream<WindowedValue<T>> dStream = unboundedDataset.getDStream();
        JavaDStream<WindowedValue<T>> outputStream;
        if (TranslationUtils.skipAssignWindows(transform, context)) {
          // do nothing.
          outputStream = dStream;
        } else {
          outputStream = dStream.transform(
              new Function<JavaRDD<WindowedValue<T>>, JavaRDD<WindowedValue<T>>>() {
            @Override
            public JavaRDD<WindowedValue<T>> call(JavaRDD<WindowedValue<T>> rdd) throws Exception {
              return rdd.map(new SparkAssignWindowFn<>(transform.getWindowFn()));
            }
          });
        }
        context.putDataset(transform,
            new UnboundedDataset<>(outputStream, unboundedDataset.getStreamSources()));
      }
    };
  }

  private static <K, V, W extends BoundedWindow> TransformEvaluator<GroupByKey<K, V>> groupByKey() {
    return new TransformEvaluator<GroupByKey<K, V>>() {
      @Override
      public void evaluate(GroupByKey<K, V> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked") UnboundedDataset<KV<K, V>> inputDataset =
            (UnboundedDataset<KV<K, V>>) context.borrowDataset(transform);
        List<Integer> streamSources = inputDataset.getStreamSources();
        JavaDStream<WindowedValue<KV<K, V>>> dStream = inputDataset.getDStream();
        @SuppressWarnings("unchecked")
        final KvCoder<K, V> coder = (KvCoder<K, V>) context.getInput(transform).getCoder();
        final SparkRuntimeContext runtimeContext = context.getRuntimeContext();
        @SuppressWarnings("unchecked")
        final WindowingStrategy<?, W> windowingStrategy =
            (WindowingStrategy<?, W>) context.getInput(transform).getWindowingStrategy();
        @SuppressWarnings("unchecked")
        final WindowFn<Object, W> windowFn = (WindowFn<Object, W>) windowingStrategy.getWindowFn();

        //--- coders.
        final WindowedValue.WindowedValueCoder<V> wvCoder =
            WindowedValue.FullWindowedValueCoder.of(coder.getValueCoder(), windowFn.windowCoder());

        //--- group by key only.
        JavaDStream<WindowedValue<KV<K, Iterable<WindowedValue<V>>>>> groupedByKeyStream =
            dStream.transform(new Function<JavaRDD<WindowedValue<KV<K, V>>>,
                JavaRDD<WindowedValue<KV<K, Iterable<WindowedValue<V>>>>>>() {
                  @Override
                  public JavaRDD<WindowedValue<KV<K, Iterable<WindowedValue<V>>>>> call(
                      JavaRDD<WindowedValue<KV<K, V>>> rdd) throws Exception {
                        return GroupCombineFunctions.groupByKeyOnly(
                            rdd, coder.getKeyCoder(), wvCoder);
                      }
                });

        //--- now group also by window.
        JavaDStream<WindowedValue<KV<K, Iterable<V>>>> outStream =
            SparkGroupAlsoByWindowViaWindowSet.groupAlsoByWindow(
                groupedByKeyStream,
                coder.getValueCoder(),
                windowingStrategy,
                runtimeContext,
                streamSources);

        context.putDataset(transform, new UnboundedDataset<>(outStream, streamSources));
      }
    };
  }

  private static <K, InputT, OutputT> TransformEvaluator<Combine.GroupedValues<K, InputT, OutputT>>
  combineGrouped() {
    return new TransformEvaluator<Combine.GroupedValues<K, InputT, OutputT>>() {
      @Override
      public void evaluate(final Combine.GroupedValues<K, InputT, OutputT> transform,
                           EvaluationContext context) {
        // get the applied combine function.
        PCollection<? extends KV<K, ? extends Iterable<InputT>>> input =
            context.getInput(transform);
        final WindowingStrategy<?, ?> windowingStrategy = input.getWindowingStrategy();
        @SuppressWarnings("unchecked")
        final CombineWithContext.KeyedCombineFnWithContext<K, InputT, ?, OutputT> fn =
            (CombineWithContext.KeyedCombineFnWithContext<K, InputT, ?, OutputT>)
                CombineFnUtil.toFnWithContext(transform.getFn());

        @SuppressWarnings("unchecked")
        UnboundedDataset<KV<K, Iterable<InputT>>> unboundedDataset =
            ((UnboundedDataset<KV<K, Iterable<InputT>>>) context.borrowDataset(transform));
        JavaDStream<WindowedValue<KV<K, Iterable<InputT>>>> dStream = unboundedDataset.getDStream();

        final SparkRuntimeContext runtimeContext = context.getRuntimeContext();
        final SparkPCollectionView pviews = context.getPViews();

        JavaDStream<WindowedValue<KV<K, OutputT>>> outStream = dStream.transform(
            new Function<JavaRDD<WindowedValue<KV<K, Iterable<InputT>>>>,
                         JavaRDD<WindowedValue<KV<K, OutputT>>>>() {
                @Override
                public JavaRDD<WindowedValue<KV<K, OutputT>>>
                    call(JavaRDD<WindowedValue<KV<K, Iterable<InputT>>>> rdd)
                        throws Exception {
                        SparkKeyedCombineFn<K, InputT, ?, OutputT> combineFnWithContext =
                            new SparkKeyedCombineFn<>(fn, runtimeContext,
                                TranslationUtils.getSideInputs(transform.getSideInputs(),
                                new JavaSparkContext(rdd.context()), pviews),
                                windowingStrategy);
                    return rdd.map(
                        new TranslationUtils.CombineGroupedValues<>(combineFnWithContext));
                  }
                });

        context.putDataset(transform,
            new UnboundedDataset<>(outStream, unboundedDataset.getStreamSources()));
      }
    };
  }

  private static <InputT, OutputT> TransformEvaluator<ParDo.Bound<InputT, OutputT>> parDo() {
    return new TransformEvaluator<ParDo.Bound<InputT, OutputT>>() {
      @Override
      public void evaluate(final ParDo.Bound<InputT, OutputT> transform,
                           final EvaluationContext context) {
        final DoFn<InputT, OutputT> doFn = transform.getFn();
        rejectSplittable(doFn);
        rejectStateAndTimers(doFn);
        final SparkRuntimeContext runtimeContext = context.getRuntimeContext();
        final WindowingStrategy<?, ?> windowingStrategy =
            context.getInput(transform).getWindowingStrategy();
        final SparkPCollectionView pviews = context.getPViews();

        @SuppressWarnings("unchecked")
        UnboundedDataset<InputT> unboundedDataset =
            ((UnboundedDataset<InputT>) context.borrowDataset(transform));
        JavaDStream<WindowedValue<InputT>> dStream = unboundedDataset.getDStream();

        final String stepName = context.getCurrentTransform().getFullName();

        JavaDStream<WindowedValue<OutputT>> outStream =
            dStream.transform(new Function<JavaRDD<WindowedValue<InputT>>,
                JavaRDD<WindowedValue<OutputT>>>() {
          @Override
          public JavaRDD<WindowedValue<OutputT>> call(JavaRDD<WindowedValue<InputT>> rdd) throws
              Exception {
            final JavaSparkContext jsc = new JavaSparkContext(rdd.context());
            final Accumulator<NamedAggregators> aggAccum =
                SparkAggregators.getNamedAggregators(jsc);
            final Accumulator<SparkMetricsContainer> metricsAccum =
                MetricsAccumulator.getInstance();
            final Map<TupleTag<?>, KV<WindowingStrategy<?, ?>, SideInputBroadcast<?>>> sideInputs =
                TranslationUtils.getSideInputs(transform.getSideInputs(),
                    jsc, pviews);
            return rdd.mapPartitions(
                new DoFnFunction<>(aggAccum, metricsAccum, stepName, doFn, runtimeContext,
                    sideInputs, windowingStrategy));
          }
        });

        context.putDataset(transform,
            new UnboundedDataset<>(outStream, unboundedDataset.getStreamSources()));
      }
    };
  }

  private static <InputT, OutputT> TransformEvaluator<ParDo.BoundMulti<InputT, OutputT>>
  multiDo() {
    return new TransformEvaluator<ParDo.BoundMulti<InputT, OutputT>>() {
      @Override
      public void evaluate(final ParDo.BoundMulti<InputT, OutputT> transform,
                           final EvaluationContext context) {
        final DoFn<InputT, OutputT> doFn = transform.getFn();
        rejectSplittable(doFn);
        rejectStateAndTimers(doFn);
        final SparkRuntimeContext runtimeContext = context.getRuntimeContext();
        final SparkPCollectionView pviews = context.getPViews();
        final WindowingStrategy<?, ?> windowingStrategy =
            context.getInput(transform).getWindowingStrategy();

        @SuppressWarnings("unchecked")
        UnboundedDataset<InputT> unboundedDataset =
            ((UnboundedDataset<InputT>) context.borrowDataset(transform));
        JavaDStream<WindowedValue<InputT>> dStream = unboundedDataset.getDStream();

        JavaPairDStream<TupleTag<?>, WindowedValue<?>> all = dStream.transformToPair(
            new Function<JavaRDD<WindowedValue<InputT>>,
                JavaPairRDD<TupleTag<?>, WindowedValue<?>>>() {
          @Override
          public JavaPairRDD<TupleTag<?>, WindowedValue<?>> call(
              JavaRDD<WindowedValue<InputT>> rdd) throws Exception {
            String stepName = context.getCurrentTransform().getFullName();
            JavaSparkContext jsc = new JavaSparkContext(rdd.context());
            final Accumulator<NamedAggregators> aggAccum =
                SparkAggregators.getNamedAggregators(jsc);
            final Accumulator<SparkMetricsContainer> metricsAccum =
                MetricsAccumulator.getInstance();
            final Map<TupleTag<?>, KV<WindowingStrategy<?, ?>, SideInputBroadcast<?>>> sideInputs =
                TranslationUtils.getSideInputs(transform.getSideInputs(),
                    JavaSparkContext.fromSparkContext(rdd.context()), pviews);
              return rdd.mapPartitionsToPair(new MultiDoFnFunction<>(aggAccum, metricsAccum,
                  stepName, doFn, runtimeContext, transform.getMainOutputTag(), sideInputs,
                  windowingStrategy));
          }
        }).cache();
        List<TaggedPValue> pct = context.getOutputs(transform);
        for (TaggedPValue e : pct) {
          @SuppressWarnings("unchecked")
          JavaPairDStream<TupleTag<?>, WindowedValue<?>> filtered =
              all.filter(new TranslationUtils.TupleTagFilter(e.getTag()));
          @SuppressWarnings("unchecked")
          // Object is the best we can do since different outputs can have different tags
          JavaDStream<WindowedValue<Object>> values =
              (JavaDStream<WindowedValue<Object>>)
                  (JavaDStream<?>) TranslationUtils.dStreamValues(filtered);
          context.putDataset(e.getValue(),
              new UnboundedDataset<>(values, unboundedDataset.getStreamSources()));
        }
      }
    };
  }

  private static final Map<Class<? extends PTransform>, TransformEvaluator<?>> EVALUATORS =
      Maps.newHashMap();

  static {
    EVALUATORS.put(Read.Unbounded.class, readUnbounded());
    EVALUATORS.put(GroupByKey.class, groupByKey());
    EVALUATORS.put(Combine.GroupedValues.class, combineGrouped());
    EVALUATORS.put(ParDo.Bound.class, parDo());
    EVALUATORS.put(ParDo.BoundMulti.class, multiDo());
    EVALUATORS.put(ConsoleIO.Write.Unbound.class, print());
    EVALUATORS.put(CreateStream.class, createFromQueue());
    EVALUATORS.put(Window.Bound.class, window());
    EVALUATORS.put(Flatten.FlattenPCollectionList.class, flattenPColl());
  }

  /**
   * Translator matches Beam transformation with the appropriate evaluator.
   */
  public static class Translator implements SparkPipelineTranslator {

    private final SparkPipelineTranslator batchTranslator;

    Translator(SparkPipelineTranslator batchTranslator) {
      this.batchTranslator = batchTranslator;
    }

    @Override
    public boolean hasTranslation(Class<? extends PTransform<?, ?>> clazz) {
      // streaming includes rdd/bounded transformations as well
      return EVALUATORS.containsKey(clazz);
    }

    @Override
    public <TransformT extends PTransform<?, ?>> TransformEvaluator<TransformT>
        translateBounded(Class<TransformT> clazz) {
      TransformEvaluator<TransformT> transformEvaluator = batchTranslator.translateBounded(clazz);
      checkState(transformEvaluator != null,
          "No TransformEvaluator registered for BOUNDED transform %s", clazz);
      return transformEvaluator;
    }

    @Override
    public <TransformT extends PTransform<?, ?>> TransformEvaluator<TransformT>
        translateUnbounded(Class<TransformT> clazz) {
      @SuppressWarnings("unchecked") TransformEvaluator<TransformT> transformEvaluator =
          (TransformEvaluator<TransformT>) EVALUATORS.get(clazz);
      checkState(transformEvaluator != null,
          "No TransformEvaluator registered for UNBOUNDED transform %s", clazz);
      return transformEvaluator;
    }
  }
}
