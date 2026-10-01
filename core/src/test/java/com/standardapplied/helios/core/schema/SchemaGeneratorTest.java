/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaGeneratorTest {

  record SimpleRecord(String name, int age) {}

  record AllPrimitives(
      String text,
      int intValue,
      Integer integerValue,
      long longValue,
      Long longObjectValue,
      double doubleValue,
      Double doubleObjectValue,
      float floatValue,
      boolean boolValue,
      Boolean boolObjectValue) {}

  record WithList(String name, List<String> tags) {}

  record WithNestedRecord(String id, SimpleRecord nested) {}

  record WithNullable(String required, @Nullable String optional) {}

  enum Color {
    RED,
    GREEN,
    BLUE
  }

  record WithEnum(String name, Color color) {}

  @Test
  void generateSimpleRecord() {
    var schema = SchemaGenerator.generate(SimpleRecord.class);

    assertEquals("object", schema.type());
    assertEquals(2, schema.properties().size());
    assertEquals("string", schema.properties().get("name").type());
    assertEquals("integer", schema.properties().get("age").type());
    assertEquals(List.of("name", "age"), schema.required());
  }

  @Test
  void generateAllPrimitives() {
    var schema = SchemaGenerator.generate(AllPrimitives.class);

    assertEquals("string", schema.properties().get("text").type());
    assertEquals("integer", schema.properties().get("intValue").type());
    assertEquals("integer", schema.properties().get("integerValue").type());
    assertEquals("integer", schema.properties().get("longValue").type());
    assertEquals("integer", schema.properties().get("longObjectValue").type());
    assertEquals("number", schema.properties().get("doubleValue").type());
    assertEquals("number", schema.properties().get("doubleObjectValue").type());
    assertEquals("number", schema.properties().get("floatValue").type());
    assertEquals("boolean", schema.properties().get("boolValue").type());
    assertEquals("boolean", schema.properties().get("boolObjectValue").type());
  }

  @Test
  void generateWithList() {
    var schema = SchemaGenerator.generate(WithList.class);

    assertEquals("array", schema.properties().get("tags").type());
    assertEquals("string", schema.properties().get("tags").items().type());
  }

  @Test
  void generateWithNestedRecord() {
    var schema = SchemaGenerator.generate(WithNestedRecord.class);

    var nested = schema.properties().get("nested");
    assertEquals("object", nested.type());
    assertEquals("string", nested.properties().get("name").type());
    assertEquals("integer", nested.properties().get("age").type());
  }

  @Test
  void generateWithNullable() {
    var schema = SchemaGenerator.generate(WithNullable.class);

    assertEquals(List.of("required"), schema.required());
    assertNotNull(schema.properties().get("optional"));
  }

  @Test
  void generateWithEnum() {
    var schema = SchemaGenerator.generate(WithEnum.class);

    var colorSchema = schema.properties().get("color");
    assertEquals("string", colorSchema.type());
    assertEquals(List.of("RED", "GREEN", "BLUE"), colorSchema.enumValues());
  }

  record SharedBadge(String label, String type) {}

  record ProfileItem(String name, List<SharedBadge> badges) {}

  record EventItem(String name, List<SharedBadge> badges) {}

  record MultiResponse(ProfileItem profile, EventItem event) {}

  @Test
  void generateWithSharedRecordAcrossSiblingBranches() {
    var schema = SchemaGenerator.generate(MultiResponse.class);

    var profileBadges = schema.properties().get("profile").properties().get("badges");
    var eventBadges = schema.properties().get("event").properties().get("badges");
    assertEquals("array", profileBadges.type());
    assertEquals("object", profileBadges.items().type());
    assertEquals("array", eventBadges.type());
    assertEquals("object", eventBadges.items().type());
  }

  record SelfReferencing(String value, SelfReferencing child) {}

  @Test
  void throwsForLeafType() {
    assertThrows(IllegalArgumentException.class, () -> SchemaGenerator.generate(String.class));
  }

  @Test
  void throwsForCircularRecordReference() {
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> SchemaGenerator.generate(SelfReferencing.class));
    assertTrue(exception.getMessage().contains("Circular record reference"));
  }

  @Description("A person profile")
  record DescribedRecord(
      @Description("Full legal name") String name, @Description("Age in years") int age) {}

  @Test
  void generateWithDescriptionOnComponents() {
    var schema = SchemaGenerator.generate(DescribedRecord.class);

    assertEquals("Full legal name", schema.properties().get("name").description());
    assertEquals("Age in years", schema.properties().get("age").description());
  }

  @Test
  void generateWithDescriptionOnType() {
    var schema = SchemaGenerator.generate(DescribedRecord.class);

    assertEquals("A person profile", schema.description());
  }

  @Test
  void toMapProducesValidJsonSchema() {
    var schema = SchemaGenerator.generate(SimpleRecord.class);
    var map = schema.toMap();

    assertEquals("object", map.get("type"));
    assertTrue(map.containsKey("properties"));
    assertTrue(map.containsKey("required"));
  }

  record WithShortAndBigInteger(short shortVal, BigInteger bigIntVal) {}

  @Test
  void generateWithShortAndBigInteger() {
    var schema = SchemaGenerator.generate(WithShortAndBigInteger.class);

    assertEquals("integer", schema.properties().get("shortVal").type());
    assertEquals("integer", schema.properties().get("bigIntVal").type());
  }

  record WithBigDecimal(BigDecimal price) {}

  @Test
  void generateWithBigDecimal() {
    var schema = SchemaGenerator.generate(WithBigDecimal.class);

    assertEquals("number", schema.properties().get("price").type());
  }

  record WithMap(String id, Map<String, Object> attributes) {}

  @Test
  void generateWithMapField() {
    var schema = SchemaGenerator.generate(WithMap.class);

    var attributes = schema.properties().get("attributes");
    assertEquals("object", attributes.type());
    assertNotNull(attributes.additionalProperties());
  }

  record WithTypedMap(Map<String, List<String>> options) {}

  @Test
  void generateWithTypedMapField() {
    var schema = SchemaGenerator.generate(WithTypedMap.class);

    var options = schema.properties().get("options");
    assertEquals("object", options.type());
    assertNotNull(options.additionalProperties(), "Map should have additionalProperties");
    assertEquals("array", options.additionalProperties().type());
    assertEquals("string", options.additionalProperties().items().type());
  }

  @Test
  void typedMapToMapProducesAdditionalProperties() {
    var schema = SchemaGenerator.generate(WithTypedMap.class);
    var map = schema.toMap();

    @SuppressWarnings("unchecked")
    var propsMap = (Map<String, Object>) map.get("properties");
    @SuppressWarnings("unchecked")
    var optionsMap = (Map<String, Object>) propsMap.get("options");
    assertEquals("object", optionsMap.get("type"));
    @SuppressWarnings("unchecked")
    var additionalProps = (Map<String, Object>) optionsMap.get("additionalProperties");
    assertNotNull(additionalProps, "toMap should include additionalProperties");
    assertEquals("array", additionalProps.get("type"));
  }

  record WithMapOfRecords(Map<String, SimpleRecord> items) {}

  @Test
  void generateWithMapOfRecords() {
    var schema = SchemaGenerator.generate(WithMapOfRecords.class);

    var items = schema.properties().get("items");
    assertEquals("object", items.type());
    var valueSchema = items.additionalProperties();
    assertNotNull(valueSchema);
    assertEquals("object", valueSchema.type());
    assertEquals("string", valueSchema.properties().get("name").type());
    assertEquals("integer", valueSchema.properties().get("age").type());
  }

  record WithArray(String name, int[] scores) {}

  @Test
  void generateWithArrayField() {
    var schema = SchemaGenerator.generate(WithArray.class);

    var scoresSchema = schema.properties().get("scores");
    assertEquals("array", scoresSchema.type());
    assertEquals("integer", scoresSchema.items().type());
  }

  record WithStringArray(String[] tags) {}

  @Test
  void generateWithStringArrayField() {
    var schema = SchemaGenerator.generate(WithStringArray.class);

    var tagsSchema = schema.properties().get("tags");
    assertEquals("array", tagsSchema.type());
    assertEquals("string", tagsSchema.items().type());
  }

  record WithListOfRecords(List<SimpleRecord> items) {}

  @Test
  void generateWithListOfRecords() {
    var schema = SchemaGenerator.generate(WithListOfRecords.class);

    var itemsSchema = schema.properties().get("items");
    assertEquals("array", itemsSchema.type());
    assertEquals("object", itemsSchema.items().type());
    assertNotNull(itemsSchema.items().properties().get("name"));
  }

  record EmptyRecord() {}

  @Test
  void generateEmptyRecord() {
    var schema = SchemaGenerator.generate(EmptyRecord.class);

    assertEquals("object", schema.type());
    assertTrue(schema.properties().isEmpty());
  }

  record WithOptional(Optional<String> opt) {}

  @Test
  void throwsForUnsupportedGenericType() {
    assertThrows(
        IllegalArgumentException.class, () -> SchemaGenerator.generate(WithOptional.class));
  }

  record WithObjectField(String name, Object payload) {}

  @Test
  void objectFieldProducesEmptyObjectSchema() {
    var schema = SchemaGenerator.generate(WithObjectField.class);

    var payload = schema.properties().get("payload");
    assertEquals("object", payload.type());
    assertTrue(payload.properties().isEmpty());
  }

  @Test
  void throwsForObjectClass() {
    assertThrows(IllegalArgumentException.class, () -> SchemaGenerator.generate(Object.class));
  }

  // --- POJO / Bean support ---

  static class ProviderInfo {
    private final String firstName;
    private final String lastName;
    private final String npi;

    ProviderInfo(String firstName, String lastName, String npi) {
      this.firstName = firstName;
      this.lastName = lastName;
      this.npi = npi;
    }

    public String firstName() {
      return firstName;
    }

    public String lastName() {
      return lastName;
    }

    public String npi() {
      return npi;
    }
  }

  record ExtractionResult(String patientName, ProviderInfo referringProvider, double confidence) {}

  @Test
  void generateWithPojoField() {
    var schema = SchemaGenerator.generate(ExtractionResult.class);

    var provider = schema.properties().get("referringProvider");
    assertEquals("object", provider.type());
    assertEquals("string", provider.properties().get("firstName").type());
    assertEquals("string", provider.properties().get("lastName").type());
    assertEquals("string", provider.properties().get("npi").type());
    assertEquals(3, provider.properties().size());
  }

  @Test
  void generateTopLevelPojo() {
    var schema = SchemaGenerator.generate(ProviderInfo.class);

    assertEquals("object", schema.type());
    assertEquals(3, schema.properties().size());
    assertEquals("string", schema.properties().get("firstName").type());
    assertEquals("string", schema.properties().get("lastName").type());
    assertEquals("string", schema.properties().get("npi").type());
  }

  static class PersonBean {
    private final String name;
    private final int age;

    PersonBean(String name, int age) {
      this.name = name;
      this.age = age;
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }
  }

  @Test
  void generateWithGetterStyleAccessors() {
    var schema = SchemaGenerator.generate(PersonBean.class);

    assertEquals("object", schema.type());
    assertEquals("string", schema.properties().get("name").type());
    assertEquals("integer", schema.properties().get("age").type());
    assertEquals(2, schema.properties().size());
  }

  static class Flags {
    public boolean isActive() {
      return true;
    }

    public Boolean isVerified() {
      return false;
    }
  }

  @Test
  void generateWithBooleanIsAccessors() {
    var schema = SchemaGenerator.generate(Flags.class);

    assertEquals("boolean", schema.properties().get("active").type());
    assertEquals("boolean", schema.properties().get("verified").type());
    assertEquals(2, schema.properties().size());
  }

  interface Identifiable {
    String id();

    String type();
  }

  @Test
  void generateForInterface() {
    var schema = SchemaGenerator.generate(Identifiable.class);

    assertEquals("object", schema.type());
    assertEquals("string", schema.properties().get("id").type());
    assertEquals("string", schema.properties().get("type").type());
    assertEquals(2, schema.properties().size());
  }

  @Description("An annotated bean")
  static class AnnotatedBean {
    @Description("The user name")
    public String name() {
      return "test";
    }

    @Nullable
    public String nickname() {
      return null;
    }
  }

  @Test
  void generatePojoWithAnnotations() {
    var schema = SchemaGenerator.generate(AnnotatedBean.class);

    assertEquals("An annotated bean", schema.description());
    assertEquals("The user name", schema.properties().get("name").description());
    assertEquals(List.of("name"), schema.required());
    assertNotNull(schema.properties().get("nickname"));
  }

  static class DuplicateAccessors {
    public String getName() {
      return "name";
    }

    public String name() {
      return "name";
    }
  }

  @Test
  void deduplicatesPropertyNames() {
    var schema = SchemaGenerator.generate(DuplicateAccessors.class);

    assertEquals(1, schema.properties().size());
    assertNotNull(schema.properties().get("name"));
  }

  static class Base {
    public String id() {
      return "1";
    }
  }

  static class Derived extends Base {
    public String label() {
      return "test";
    }
  }

  @Test
  void generateWithInheritance() {
    var schema = SchemaGenerator.generate(Derived.class);

    assertEquals("string", schema.properties().get("id").type());
    assertEquals("string", schema.properties().get("label").type());
    assertEquals(2, schema.properties().size());
  }

  static class CircularPojo {
    public String name() {
      return "test";
    }

    public CircularPojo self() {
      return this;
    }
  }

  @Test
  void throwsForCircularPojoReference() {
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> SchemaGenerator.generate(CircularPojo.class));
    assertTrue(exception.getMessage().contains("Circular reference detected"));
  }

  @Test
  void beanPropertiesAreSortedAlphabetically() {
    var schema = SchemaGenerator.generate(ProviderInfo.class);

    var propertyNames = List.copyOf(schema.properties().keySet());
    assertEquals(List.of("firstName", "lastName", "npi"), propertyNames);
  }

  static class MixedAccessorStyles {
    public String getFirstName() {
      return "John";
    }

    public int age() {
      return 30;
    }

    public boolean isActive() {
      return true;
    }
  }

  @Test
  void generateWithMixedAccessorStyles() {
    var schema = SchemaGenerator.generate(MixedAccessorStyles.class);

    assertEquals("boolean", schema.properties().get("active").type());
    assertEquals("integer", schema.properties().get("age").type());
    assertEquals("string", schema.properties().get("firstName").type());
    assertEquals(3, schema.properties().size());
  }

  record RecordWithPojoList(String id, List<ProviderInfo> providers) {}

  @Test
  void generateWithListOfPojos() {
    var schema = SchemaGenerator.generate(RecordWithPojoList.class);

    var providersSchema = schema.properties().get("providers");
    assertEquals("array", providersSchema.type());
    assertEquals("object", providersSchema.items().type());
    assertNotNull(providersSchema.items().properties().get("firstName"));
  }

  abstract static class AbstractEntity {
    public abstract String id();

    public abstract String type();
  }

  @Test
  void generateForAbstractClass() {
    var schema = SchemaGenerator.generate(AbstractEntity.class);

    assertEquals("object", schema.type());
    assertEquals("string", schema.properties().get("id").type());
    assertEquals("string", schema.properties().get("type").type());
  }

  // --- Cross-type nesting coverage ---

  static class Address {
    private final String street;
    private final String city;

    Address(String street, String city) {
      this.street = street;
      this.city = city;
    }

    public String street() {
      return street;
    }

    public String city() {
      return city;
    }
  }

  static class Contact {
    private final String phone;
    private final Address address;

    Contact(String phone, Address address) {
      this.phone = phone;
      this.address = address;
    }

    public String phone() {
      return phone;
    }

    public Address address() {
      return address;
    }
  }

  @Test
  void generatePojoInPojo() {
    var schema = SchemaGenerator.generate(Contact.class);

    assertEquals("string", schema.properties().get("phone").type());
    var address = schema.properties().get("address");
    assertEquals("object", address.type());
    assertEquals("string", address.properties().get("street").type());
    assertEquals("string", address.properties().get("city").type());
  }

  static class PojoWithRecord {
    public String label() {
      return "test";
    }

    public SimpleRecord nested() {
      return new SimpleRecord("a", 1);
    }
  }

  @Test
  void generateRecordInPojo() {
    var schema = SchemaGenerator.generate(PojoWithRecord.class);

    assertEquals("string", schema.properties().get("label").type());
    var nested = schema.properties().get("nested");
    assertEquals("object", nested.type());
    assertEquals("string", nested.properties().get("name").type());
    assertEquals("integer", nested.properties().get("age").type());
  }

  static class PojoWithEnum {
    public String name() {
      return "test";
    }

    public Color color() {
      return Color.RED;
    }
  }

  @Test
  void generateEnumInPojo() {
    var schema = SchemaGenerator.generate(PojoWithEnum.class);

    var colorSchema = schema.properties().get("color");
    assertEquals("string", colorSchema.type());
    assertEquals(List.of("RED", "GREEN", "BLUE"), colorSchema.enumValues());
  }

  interface HasAddress {
    Address address();
  }

  record PersonWithInterface(String name, HasAddress contact) {}

  @Test
  void generateInterfaceInRecord() {
    var schema = SchemaGenerator.generate(PersonWithInterface.class);

    var contact = schema.properties().get("contact");
    assertEquals("object", contact.type());
    var address = contact.properties().get("address");
    assertEquals("object", address.type());
    assertEquals("string", address.properties().get("street").type());
  }

  // --- Depth 3+ nesting ---

  record Level3Record(String value) {}

  static class Level2Pojo {
    public String label() {
      return "mid";
    }

    public Level3Record inner() {
      return new Level3Record("deep");
    }
  }

  record Level1Record(String id, Level2Pojo middle) {}

  @Test
  void generateThreeLevelMixedNesting() {
    var schema = SchemaGenerator.generate(Level1Record.class);

    var middle = schema.properties().get("middle");
    assertEquals("object", middle.type());
    assertEquals("string", middle.properties().get("label").type());

    var inner = middle.properties().get("inner");
    assertEquals("object", inner.type());
    assertEquals("string", inner.properties().get("value").type());
  }

  static class Depth4Leaf {
    public int score() {
      return 100;
    }
  }

  record Depth3(String tag, Depth4Leaf leaf) {}

  static class Depth2 {
    public Depth3 nested() {
      return new Depth3("x", new Depth4Leaf());
    }
  }

  record Depth1(String id, Depth2 child) {}

  @Test
  void generateFourLevelAlternatingNesting() {
    var schema = SchemaGenerator.generate(Depth1.class);

    var child = schema.properties().get("child");
    assertEquals("object", child.type());

    var nested = child.properties().get("nested");
    assertEquals("object", nested.type());
    assertEquals("string", nested.properties().get("tag").type());

    var leaf = nested.properties().get("leaf");
    assertEquals("object", leaf.type());
    assertEquals("integer", leaf.properties().get("score").type());
  }

  // --- Cross-type circular references ---

  static class PojoPointingToRecord {
    public String name() {
      return "test";
    }

    public RecordPointingToPojo link() {
      return null;
    }
  }

  record RecordPointingToPojo(String id, PojoPointingToRecord back) {}

  @Test
  void throwsForCrossTypeCircularReference() {
    assertThrows(
        IllegalArgumentException.class, () -> SchemaGenerator.generate(RecordPointingToPojo.class));
    assertThrows(
        IllegalArgumentException.class, () -> SchemaGenerator.generate(PojoPointingToRecord.class));
  }

  static class PojoA {
    public PojoB other() {
      return null;
    }
  }

  static class PojoB {
    public PojoA other() {
      return null;
    }
  }

  @Test
  void throwsForTransitivePojoCircularReference() {
    assertThrows(IllegalArgumentException.class, () -> SchemaGenerator.generate(PojoA.class));
  }

  record WithIntegerKeyMap(Map<Integer, String> data) {}

  @Test
  void throwsForNonStringMapKey() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaGenerator.generate(WithIntegerKeyMap.class));
    assertTrue(ex.getMessage().contains("Map key type must be String"));
  }

  record WithWildcardMap(Map<String, ?> data) {}

  @Test
  void throwsForWildcardMapValueType() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> SchemaGenerator.generate(WithWildcardMap.class));
    assertTrue(ex.getMessage().contains("Wildcard types"));
  }
}
