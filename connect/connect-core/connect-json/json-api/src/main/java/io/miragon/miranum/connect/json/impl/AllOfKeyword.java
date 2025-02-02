package io.miragon.miranum.connect.json.impl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.util.stream.Collectors.*;

public class AllOfKeyword extends AbstractKeyword {

    public AllOfKeyword() {
        super("allOf");
    }

    @Override
    public AllOfValidator newValidator(final String schemaPath, final JsonNode schemaNode, final JsonSchema parentSchema, final ValidationContext validationContext) {
        return new AllOfValidator(schemaPath, schemaNode, parentSchema, validationContext);
    }


    /**
     * This class is copied from com.networknt.schema.AllOfValidator
     */
    static class AllOfValidator extends BaseJsonValidator implements JsonValidator {
        private static final Logger logger = LoggerFactory.getLogger(com.networknt.schema.AllOfValidator.class);

        private final List<JsonSchema> schemas = new ArrayList<JsonSchema>();

        public AllOfValidator(final String schemaPath, final JsonNode schemaNode, final JsonSchema parentSchema, final ValidationContext validationContext) {
            super(schemaPath, schemaNode, parentSchema, ValidatorTypeCode.ALL_OF, validationContext);
            this.validationContext = validationContext;
            final int size = schemaNode.size();
            for (int i = 0; i < size; i++) {
                this.schemas.add(new JsonSchema(validationContext,
                        parentSchema.getSchemaPath() + "/" + this.getValidatorType().getValue() + "/" + i,
                        parentSchema.getCurrentUri(),
                        schemaNode.get(i),
                        parentSchema));
            }
        }

        public Set<ValidationMessage> validate(final JsonNode node, final JsonNode rootNode, final String at) {
            this.debug(logger, node, rootNode, at);

            // get the Validator state object storing validation data
            final ValidatorState state = (ValidatorState) CollectorContext.getInstance().get(ValidatorState.VALIDATOR_STATE_KEY);

            final Set<ValidationMessage> childSchemaErrors = new LinkedHashSet<ValidationMessage>();

            // As AllOf might contain multiple schemas take a backup of evaluatedProperties.
            final Object backupEvaluatedProperties = CollectorContext.getInstance().get(UnEvaluatedPropertiesValidator.EVALUATED_PROPERTIES);

            final List<String> totalEvaluatedProperties = new ArrayList<>();

            for (final JsonSchema schema : this.schemas) {
                try {
                    // Make the evaluatedProperties list empty.
                    CollectorContext.getInstance().add(UnEvaluatedPropertiesValidator.EVALUATED_PROPERTIES, new ArrayList<>());

                    Set<ValidationMessage> localErrors = new HashSet<>();

                    if (!state.isWalkEnabled()) {
                        localErrors = schema.validate(node, rootNode, at);
                    } else {
                        localErrors = schema.walk(node, rootNode, at, true);
                    }

                    childSchemaErrors.addAll(localErrors);

                    // Keep Collecting total evaluated properties.
                    if (localErrors.isEmpty()) {
                        totalEvaluatedProperties.addAll((List<String>) CollectorContext.getInstance().get(UnEvaluatedPropertiesValidator.EVALUATED_PROPERTIES));
                    }

                    if (this.validationContext.getConfig().isOpenAPI3StyleDiscriminators()) {
                        final Iterator<JsonNode> arrayElements = this.schemaNode.elements();
                        while (arrayElements.hasNext()) {
                            final ObjectNode allOfEntry = (ObjectNode) arrayElements.next();
                            final JsonNode $ref = allOfEntry.get("$ref");
                            if (null != $ref) {
                                final ValidationContext.DiscriminatorContext currentDiscriminatorContext = this.validationContext
                                        .getCurrentDiscriminatorContext();
                                if (null != currentDiscriminatorContext) {
                                    final ObjectNode discriminator = currentDiscriminatorContext
                                            .getDiscriminatorForPath(allOfEntry.get("$ref").asText());
                                    if (null != discriminator) {
                                        registerAndMergeDiscriminator(currentDiscriminatorContext, discriminator, this.parentSchema, at);
                                        // now we have to check whether we have hit the right target
                                        final String discriminatorPropertyName = discriminator.get("propertyName").asText();
                                        final JsonNode discriminatorNode = node.get(discriminatorPropertyName);
                                        final String discriminatorPropertyValue = discriminatorNode == null
                                                ? null
                                                : discriminatorNode.textValue();

                                        final JsonSchema jsonSchema = this.parentSchema;
                                        checkDiscriminatorMatch(
                                                currentDiscriminatorContext,
                                                discriminator,
                                                discriminatorPropertyValue,
                                                jsonSchema);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    if (childSchemaErrors.isEmpty()) {
                        final List<String> backupEvaluatedPropertiesList = (backupEvaluatedProperties == null ? new ArrayList<>() : (List<String>) backupEvaluatedProperties);
                        backupEvaluatedPropertiesList.addAll(totalEvaluatedProperties);
                        CollectorContext.getInstance().add(UnEvaluatedPropertiesValidator.EVALUATED_PROPERTIES, backupEvaluatedPropertiesList);
                    } else {
                        CollectorContext.getInstance().add(UnEvaluatedPropertiesValidator.EVALUATED_PROPERTIES, backupEvaluatedProperties);
                    }
                }
            }

            //The par was changed to make it work with the Miranum JSON API

            // exclude additional property errors
            final Set<ValidationMessage> errorsWithoutAddtionalProperties = childSchemaErrors.stream()
                    .filter(error -> !error.getCode().equals(ValidatorTypeCode.ADDITIONAL_PROPERTIES.getErrorCode()))
                    .collect(toSet());

            // filter additional property errors where all schemas are invalid
            final Set<ValidationMessage> additionalPropertyErrors = childSchemaErrors.stream()
                    // filter out additionalProperties errors
                    .filter(error -> error.getCode().equals(ValidatorTypeCode.ADDITIONAL_PROPERTIES.getErrorCode()))
                    // group by same path
                    .collect(groupingBy(this::getErrorPath, toSet()))
                    .values().stream()
                    // filter if all schemas are invalid
                    .filter(set -> set.size() >= this.schemas.size())
                    .flatMap(Collection::stream).collect(toUnmodifiableSet());

            errorsWithoutAddtionalProperties.addAll(additionalPropertyErrors);

            return Collections.unmodifiableSet(errorsWithoutAddtionalProperties);
        }

        private String getErrorPath(final ValidationMessage message) {
            //additionalPropertyErros should only contain one argument
            if (message.getArguments().length != 1) {
                throw new IllegalArgumentException("Expected exactly one argument for error message: " + message);
            }
            return message.getPath() + "." + message.getArguments()[0];
        }

        @Override
        public Set<ValidationMessage> walk(final JsonNode node, final JsonNode rootNode, final String at, final boolean shouldValidateSchema) {
            if (shouldValidateSchema) {
                return this.validate(node, rootNode, at);
            }
            for (final JsonSchema schema : this.schemas) {
                // Walk through the schema
                schema.walk(node, rootNode, at, false);
            }
            return Collections.emptySet();
        }

        @Override
        public void preloadJsonSchema() {
            this.preloadJsonSchemas(this.schemas);
        }
    }


}
