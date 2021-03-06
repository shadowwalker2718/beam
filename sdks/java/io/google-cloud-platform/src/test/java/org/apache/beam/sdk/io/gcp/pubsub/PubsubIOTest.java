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
package org.apache.beam.sdk.io.gcp.pubsub;

import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.google.api.client.util.Clock;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.io.AvroGeneratedUser;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubClient.IncomingMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubClient.SubscriptionPath;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO.Read;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubTestClient.PubsubTestClientFactory;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.UsesUnboundedPCollections;
import org.apache.beam.sdk.testing.ValidatesRunner;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.DisplayDataEvaluator;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.vendor.guava.v20_0.com.google.common.base.MoreObjects;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.model.Statement;
import org.testng.collections.Lists;

/** Tests for PubsubIO Read and Write transforms. */
@RunWith(JUnit4.class)
public class PubsubIOTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testPubsubIOGetName() {
    assertEquals(
        "PubsubIO.Read",
        PubsubIO.readStrings().fromTopic("projects/myproject/topics/mytopic").getName());
    assertEquals(
        "PubsubIO.Write",
        PubsubIO.writeStrings().to("projects/myproject/topics/mytopic").getName());
  }

  @Test
  public void testTopicValidationSuccess() throws Exception {
    PubsubIO.readStrings().fromTopic("projects/my-project/topics/abc");
    PubsubIO.readStrings().fromTopic("projects/my-project/topics/ABC");
    PubsubIO.readStrings().fromTopic("projects/my-project/topics/AbC-DeF");
    PubsubIO.readStrings().fromTopic("projects/my-project/topics/AbC-1234");
    PubsubIO.readStrings().fromTopic("projects/my-project/topics/AbC-1234-_.~%+-_.~%+-_.~%+-abc");
    PubsubIO.readStrings()
        .fromTopic(
            new StringBuilder()
                .append("projects/my-project/topics/A-really-long-one-")
                .append(
                    "111111111111111111111111111111111111111111111111111111111111111111111111111111111")
                .append(
                    "111111111111111111111111111111111111111111111111111111111111111111111111111111111")
                .append(
                    "11111111111111111111111111111111111111111111111111111111111111111111111111")
                .toString());
  }

  @Test
  public void testTopicValidationBadCharacter() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    PubsubIO.readStrings().fromTopic("projects/my-project/topics/abc-*-abc");
  }

  @Test
  public void testTopicValidationTooLong() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    PubsubIO.readStrings()
        .fromTopic(
            new StringBuilder()
                .append("projects/my-project/topics/A-really-long-one-")
                .append(
                    "111111111111111111111111111111111111111111111111111111111111111111111111111111111")
                .append(
                    "111111111111111111111111111111111111111111111111111111111111111111111111111111111")
                .append(
                    "1111111111111111111111111111111111111111111111111111111111111111111111111111")
                .toString());
  }

  @Test
  public void testReadTopicDisplayData() {
    String topic = "projects/project/topics/topic";
    PubsubIO.Read<String> read =
        PubsubIO.readStrings()
            .fromTopic(StaticValueProvider.of(topic))
            .withTimestampAttribute("myTimestamp")
            .withIdAttribute("myId");

    DisplayData displayData = DisplayData.from(read);

    assertThat(displayData, hasDisplayItem("topic", topic));
    assertThat(displayData, hasDisplayItem("timestampAttribute", "myTimestamp"));
    assertThat(displayData, hasDisplayItem("idAttribute", "myId"));
  }

  @Test
  public void testReadSubscriptionDisplayData() {
    String subscription = "projects/project/subscriptions/subscription";
    PubsubIO.Read<String> read =
        PubsubIO.readStrings()
            .fromSubscription(StaticValueProvider.of(subscription))
            .withTimestampAttribute("myTimestamp")
            .withIdAttribute("myId");

    DisplayData displayData = DisplayData.from(read);

    assertThat(displayData, hasDisplayItem("subscription", subscription));
    assertThat(displayData, hasDisplayItem("timestampAttribute", "myTimestamp"));
    assertThat(displayData, hasDisplayItem("idAttribute", "myId"));
  }

  @Test
  public void testNullTopic() {
    String subscription = "projects/project/subscriptions/subscription";
    PubsubIO.Read<String> read =
        PubsubIO.readStrings().fromSubscription(StaticValueProvider.of(subscription));
    assertNull(read.getTopicProvider());
    assertNotNull(read.getSubscriptionProvider());
    assertNotNull(DisplayData.from(read));
  }

  @Test
  public void testNullSubscription() {
    String topic = "projects/project/topics/topic";
    PubsubIO.Read<String> read = PubsubIO.readStrings().fromTopic(StaticValueProvider.of(topic));
    assertNotNull(read.getTopicProvider());
    assertNull(read.getSubscriptionProvider());
    assertNotNull(DisplayData.from(read));
  }

  @Test
  public void testValueProviderSubscription() {
    StaticValueProvider<String> provider =
        StaticValueProvider.of("projects/project/subscriptions/subscription");
    Read<String> pubsubRead = PubsubIO.readStrings().fromSubscription(provider);
    Pipeline.create().apply(pubsubRead);
    assertThat(pubsubRead.getSubscriptionProvider(), not(nullValue()));
    assertThat(pubsubRead.getSubscriptionProvider().isAccessible(), is(true));
    assertThat(pubsubRead.getSubscriptionProvider().get().asPath(), equalTo(provider.get()));
  }

  @Test
  public void testRuntimeValueProviderSubscription() {
    TestPipeline pipeline = TestPipeline.create();
    ValueProvider<String> subscription =
        pipeline.newProvider("projects/project/subscriptions/subscription");
    Read<String> pubsubRead = PubsubIO.readStrings().fromSubscription(subscription);
    pipeline.apply(pubsubRead);
    assertThat(pubsubRead.getSubscriptionProvider(), not(nullValue()));
    assertThat(pubsubRead.getSubscriptionProvider().isAccessible(), is(false));
  }

  @Test
  public void testValueProviderTopic() {
    StaticValueProvider<String> provider = StaticValueProvider.of("projects/project/topics/topic");
    Read<String> pubsubRead = PubsubIO.readStrings().fromTopic(provider);
    Pipeline.create().apply(pubsubRead);
    assertThat(pubsubRead.getTopicProvider(), not(nullValue()));
    assertThat(pubsubRead.getTopicProvider().isAccessible(), is(true));
    assertThat(pubsubRead.getTopicProvider().get().asPath(), equalTo(provider.get()));
  }

  @Test
  public void testRuntimeValueProviderTopic() {
    TestPipeline pipeline = TestPipeline.create();
    ValueProvider<String> topic = pipeline.newProvider("projects/project/topics/topic");
    Read<String> pubsubRead = PubsubIO.readStrings().fromTopic(topic);
    pipeline.apply(pubsubRead);
    assertThat(pubsubRead.getTopicProvider(), not(nullValue()));
    assertThat(pubsubRead.getTopicProvider().isAccessible(), is(false));
  }

  @Test
  @Category({ValidatesRunner.class, UsesUnboundedPCollections.class})
  public void testPrimitiveReadDisplayData() {
    DisplayDataEvaluator evaluator = DisplayDataEvaluator.create();
    Set<DisplayData> displayData;
    PubsubIO.Read<String> baseRead = PubsubIO.readStrings();

    // Reading from a subscription.
    PubsubIO.Read<String> read =
        baseRead.fromSubscription("projects/project/subscriptions/subscription");
    displayData = evaluator.displayDataForPrimitiveSourceTransforms(read);
    assertThat(
        "PubsubIO.Read should include the subscription in its primitive display data",
        displayData,
        hasItem(hasDisplayItem("subscription")));

    // Reading from a topic.
    read = baseRead.fromTopic("projects/project/topics/topic");
    displayData = evaluator.displayDataForPrimitiveSourceTransforms(read);
    assertThat(
        "PubsubIO.Read should include the topic in its primitive display data",
        displayData,
        hasItem(hasDisplayItem("topic")));
  }

  @Test
  public void testWriteDisplayData() {
    String topic = "projects/project/topics/topic";
    PubsubIO.Write<?> write =
        PubsubIO.writeStrings()
            .to(topic)
            .withTimestampAttribute("myTimestamp")
            .withIdAttribute("myId");

    DisplayData displayData = DisplayData.from(write);

    assertThat(displayData, hasDisplayItem("topic", topic));
    assertThat(displayData, hasDisplayItem("timestampAttribute", "myTimestamp"));
    assertThat(displayData, hasDisplayItem("idAttribute", "myId"));
  }

  @Test
  @Category(ValidatesRunner.class)
  public void testPrimitiveWriteDisplayData() {
    DisplayDataEvaluator evaluator = DisplayDataEvaluator.create();
    PubsubIO.Write<?> write = PubsubIO.writeStrings().to("projects/project/topics/topic");

    Set<DisplayData> displayData = evaluator.displayDataForPrimitiveTransforms(write);
    assertThat(
        "PubsubIO.Write should include the topic in its primitive display data",
        displayData,
        hasItem(hasDisplayItem("topic")));
  }

  static class GenericClass {
    int intField;
    String stringField;

    public GenericClass() {}

    public GenericClass(int intField, String stringField) {
      this.intField = intField;
      this.stringField = stringField;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("intField", intField)
          .add("stringField", stringField)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(intField, stringField);
    }

    @Override
    public boolean equals(Object other) {
      if (other == null || !(other instanceof GenericClass)) {
        return false;
      }
      GenericClass o = (GenericClass) other;
      return Objects.equals(intField, o.intField) && Objects.equals(stringField, o.stringField);
    }
  }

  private transient PipelineOptions options;
  private static final SubscriptionPath SUBSCRIPTION =
      PubsubClient.subscriptionPathFromName("test-project", "testSubscription");
  private static final Clock CLOCK = (Clock & Serializable) () -> 673L;
  transient TestPipeline readPipeline;

  private static final String SCHEMA_STRING =
      "{\"namespace\": \"example.avro\",\n"
          + " \"type\": \"record\",\n"
          + " \"name\": \"AvroGeneratedUser\",\n"
          + " \"fields\": [\n"
          + "     {\"name\": \"name\", \"type\": \"string\"},\n"
          + "     {\"name\": \"favorite_number\", \"type\": [\"int\", \"null\"]},\n"
          + "     {\"name\": \"favorite_color\", \"type\": [\"string\", \"null\"]}\n"
          + " ]\n"
          + "}";

  private static final Schema SCHEMA = new Schema.Parser().parse(SCHEMA_STRING);

  @Rule
  public final transient TestRule setupPipeline =
      new TestRule() {
        @Override
        public Statement apply(final Statement base, final Description description) {
          // We need to set up the temporary folder, and then set up the TestPipeline based on the
          // chosen folder. Unfortunately, since rule evaluation order is unspecified and unrelated
          // to field order, and is separate from construction, that requires manually creating this
          // TestRule.
          Statement withPipeline =
              new Statement() {
                @Override
                public void evaluate() throws Throwable {
                  options = TestPipeline.testingPipelineOptions();
                  options.as(PubsubOptions.class).setProject("test-project");
                  readPipeline = TestPipeline.fromOptions(options);
                  readPipeline.apply(base, description).evaluate();
                }
              };
          return withPipeline;
        }
      };

  private <T> void setupTestClient(List<T> inputs, Coder<T> coder) {
    List<IncomingMessage> messages =
        inputs
            .stream()
            .map(
                t -> {
                  try {
                    return CoderUtils.encodeToByteArray(coder, t);
                  } catch (CoderException e) {
                    throw new RuntimeException(e);
                  }
                })
            .map(
                ba ->
                    new IncomingMessage(
                        ba,
                        null,
                        1234L,
                        0,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()))
            .collect(Collectors.toList());

    clientFactory = PubsubTestClient.createFactoryForPull(CLOCK, SUBSCRIPTION, 60, messages);
  }

  private PubsubTestClientFactory clientFactory;

  @After
  public void after() throws IOException {
    if (clientFactory != null) {
      clientFactory.close();
      clientFactory = null;
    }
  }

  @Test
  public void testAvroGenericRecords() {
    AvroCoder<GenericRecord> coder = AvroCoder.of(GenericRecord.class, SCHEMA);
    List<GenericRecord> inputs =
        ImmutableList.of(
            new AvroGeneratedUser("Bob", 256, null),
            new AvroGeneratedUser("Alice", 128, null),
            new AvroGeneratedUser("Ted", null, "white"));
    setupTestClient(inputs, coder);
    PCollection<GenericRecord> read =
        readPipeline.apply(
            PubsubIO.readAvroGenericRecords(SCHEMA)
                .fromSubscription(SUBSCRIPTION.getPath())
                .withClock(CLOCK)
                .withClientFactory(clientFactory));
    PAssert.that(read).containsInAnyOrder(inputs);
    readPipeline.run();
  }

  @Test
  public void testAvroPojo() {
    AvroCoder<GenericClass> coder = AvroCoder.of(GenericClass.class);
    List<GenericClass> inputs =
        Lists.newArrayList(new GenericClass(1, "foo"), new GenericClass(2, "bar"));
    setupTestClient(inputs, coder);
    PCollection<GenericClass> read =
        readPipeline.apply(
            PubsubIO.readAvrosWithBeamSchema(GenericClass.class)
                .fromSubscription(SUBSCRIPTION.getPath())
                .withClock(CLOCK)
                .withClientFactory(clientFactory));
    PAssert.that(read).containsInAnyOrder(inputs);
    readPipeline.run();
  }

  @Test
  public void testAvroSpecificRecord() {
    AvroCoder<AvroGeneratedUser> coder = AvroCoder.of(AvroGeneratedUser.class);
    List<AvroGeneratedUser> inputs =
        ImmutableList.of(
            new AvroGeneratedUser("Bob", 256, null),
            new AvroGeneratedUser("Alice", 128, null),
            new AvroGeneratedUser("Ted", null, "white"));
    setupTestClient(inputs, coder);
    PCollection<AvroGeneratedUser> read =
        readPipeline.apply(
            PubsubIO.readAvrosWithBeamSchema(AvroGeneratedUser.class)
                .fromSubscription(SUBSCRIPTION.getPath())
                .withClock(CLOCK)
                .withClientFactory(clientFactory));
    PAssert.that(read).containsInAnyOrder(inputs);
    readPipeline.run();
  }
}
