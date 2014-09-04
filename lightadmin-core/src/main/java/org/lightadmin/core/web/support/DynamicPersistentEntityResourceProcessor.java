/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lightadmin.core.web.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.lightadmin.api.config.utils.EntityNameExtractor;
import org.lightadmin.core.config.domain.DomainTypeAdministrationConfiguration;
import org.lightadmin.core.config.domain.DomainTypeBasicConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfiguration;
import org.lightadmin.core.config.domain.field.FieldMetadata;
import org.lightadmin.core.config.domain.unit.DomainConfigurationUnitType;
import org.lightadmin.core.persistence.metamodel.PersistentPropertyType;
import org.lightadmin.core.storage.FileResourceStorage;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.SimplePropertyHandler;
import org.springframework.data.rest.webmvc.PersistentEntityResource;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.ResourceProcessor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Maps.newLinkedHashMap;
import static org.lightadmin.core.config.domain.configuration.support.ExceptionAwareTransformer.exceptionAwareNameExtractor;
import static org.lightadmin.core.config.domain.field.FieldMetadataUtils.customFields;
import static org.lightadmin.core.config.domain.field.FieldMetadataUtils.transientFields;
import static org.lightadmin.core.config.domain.unit.DomainConfigurationUnitType.*;
import static org.lightadmin.core.persistence.metamodel.PersistentPropertyType.FILE;

@SuppressWarnings(value = {"unchecked", "unused"})
public class DynamicPersistentEntityResourceProcessor implements ResourceProcessor<PersistentEntityResource<?>> {

    private final GlobalAdministrationConfiguration adminConfiguration;
    private final DynamicRepositoryEntityLinks entityLinks;
    private final DomainEntityLinks domainEntityLinks;
    private final FileResourceStorage fileResourceStorage;

    public DynamicPersistentEntityResourceProcessor(GlobalAdministrationConfiguration adminConfiguration, FileResourceStorage fileResourceStorage, DynamicRepositoryEntityLinks entityLinks, DomainEntityLinks domainEntityLinks) {
        this.adminConfiguration = adminConfiguration;
        this.domainEntityLinks = domainEntityLinks;
        this.entityLinks = entityLinks;
        this.fileResourceStorage = fileResourceStorage;
    }

    @Override
    public PersistentEntityResource<?> process(PersistentEntityResource<?> persistentEntityResource) {
        PersistentEntity persistentEntity = persistentEntityResource.getPersistentEntity();
        Object value = persistentEntityResource.getContent();
        Link[] links = persistentEntityResource.getLinks().toArray(new Link[persistentEntityResource.getLinks().size()]);

        String stringRepresentation = stringRepresentation(value, persistentEntity);
        Link domainLink = domainLink(persistentEntityResource);
        boolean managedDomainType = adminConfiguration.isManagedDomainType(persistentEntity.getType());
        String primaryKey = persistentEntity.getIdProperty().getName();

        Map<DomainConfigurationUnitType, Map<String, Object>> dynamicProperties = dynamicPropertiesPerUnit(value, persistentEntity);

        PersistentEntityWrapper persistentEntityWrapper = new PersistentEntityWrapper(value, dynamicProperties, stringRepresentation, domainLink, managedDomainType, primaryKey);

        return new PersistentEntityResource<Object>(persistentEntity, persistentEntityWrapper, links);
    }

    private String stringRepresentation(Object value, PersistentEntity persistentEntity) {
        DomainTypeBasicConfiguration domainTypeBasicConfiguration = adminConfiguration.forDomainType(persistentEntity.getType());
        EntityNameExtractor nameExtractor = domainTypeBasicConfiguration.getEntityConfiguration().getNameExtractor();

        return exceptionAwareNameExtractor(nameExtractor, domainTypeBasicConfiguration).apply(value);
    }

    private Link domainLink(PersistentEntityResource persistentEntityResource) {
        PersistentEntity persistentEntity = persistentEntityResource.getPersistentEntity();
        if (domainEntityLinks.supports(persistentEntity.getType())) {
            return domainEntityLinks.linkFor(persistentEntityResource);
        }
        return null;
    }

    private Map<DomainConfigurationUnitType, Map<String, Object>> dynamicPropertiesPerUnit(Object value, PersistentEntity persistentEntity) {
        if (!adminConfiguration.isManagedDomainType(persistentEntity.getType())) {
            return Collections.emptyMap();
        }

        DomainTypeAdministrationConfiguration managedDomainTypeConfiguration = adminConfiguration.forManagedDomainType(persistentEntity.getType());

        List<DomainConfigurationUnitType> units = newArrayList(LIST_VIEW, FORM_VIEW, SHOW_VIEW, QUICK_VIEW);

        List<PersistentProperty> persistentProperties = findPersistentFileProperties(persistentEntity);

        Map<DomainConfigurationUnitType, Map<String, Object>> dynamicPropertiesPerUnit = newHashMap();
        for (DomainConfigurationUnitType unit : units) {
            Map<String, Object> dynamicProperties = newLinkedHashMap();
            for (PersistentProperty persistentProperty : persistentProperties) {
                dynamicProperties.put(persistentProperty.getName(), evaluateFilePropertyValue(persistentProperty, value));
            }
            for (FieldMetadata customField : customFields(managedDomainTypeConfiguration.fieldsForUnit(unit))) {
                dynamicProperties.put(customField.getUuid(), customField.getValue(value));
            }
            for (FieldMetadata transientField : transientFields(managedDomainTypeConfiguration.fieldsForUnit(unit))) {
                dynamicProperties.put(transientField.getUuid(), transientField.getValue(value));
            }
            dynamicPropertiesPerUnit.put(unit, dynamicProperties);
        }
        return dynamicPropertiesPerUnit;
    }

    private List<PersistentProperty> findPersistentFileProperties(PersistentEntity persistentEntity) {
        final List<PersistentProperty> result = newArrayList();
        persistentEntity.doWithProperties(new SimplePropertyHandler() {
            @Override
            public void doWithPersistentProperty(PersistentProperty<?> property) {
                if (PersistentPropertyType.forPersistentProperty(property) == FILE) {
                    result.add(property);
                }
            }
        });
        return result;
    }

    private FilePropertyValue evaluateFilePropertyValue(PersistentProperty persistentProperty, Object value) {
        try {
            if (!fileResourceStorage.fileExists(value, persistentProperty)) {
                return new FilePropertyValue(false);
            }
            return new FilePropertyValue(entityLinks.linkForFilePropertyLink(value, persistentProperty));
        } catch (Exception e) {
            return null;
        }
    }

    static class PersistentEntityWrapper {
        private String stringRepresentation;
        private boolean managedDomainType;
        private String primaryKey;
        private Link domainLink;
        private Object persistentEntity;
        private Map<DomainConfigurationUnitType, Map<String, Object>> dynamicProperties;

        public PersistentEntityWrapper(Object persistentEntity, Map<DomainConfigurationUnitType, Map<String, Object>> dynamicProperties, String stringRepresentation, Link domainLink, boolean managedDomainType, String primaryKey) {
            this.stringRepresentation = stringRepresentation;
            this.domainLink = domainLink;
            this.managedDomainType = managedDomainType;
            this.persistentEntity = persistentEntity;
            this.dynamicProperties = dynamicProperties;
            this.primaryKey = primaryKey;
        }

        @JsonProperty("string_representation")
        public String getStringRepresentation() {
            return stringRepresentation;
        }

        @JsonProperty("primary_key")
        public String getPrimaryKey() {
            return primaryKey;
        }

        @JsonProperty("managed_type")
        public boolean isManagedDomainType() {
            return managedDomainType;
        }

        @JsonProperty("domain_link")
        @JsonInclude(NON_NULL)
        public Link getDomainLink() {
            return domainLink;
        }

        @JsonProperty("original_properties")
        public Object getPersistentEntity() {
            return persistentEntity;
        }

        @JsonProperty("dynamic_properties")
        @JsonInclude(NON_EMPTY)
        public Map<DomainConfigurationUnitType, Map<String, Object>> getDynamicProperties() {
            return dynamicProperties;
        }
    }
}