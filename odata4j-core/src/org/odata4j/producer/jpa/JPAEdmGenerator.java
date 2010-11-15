package org.odata4j.producer.jpa;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManagerFactory;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.CollectionAttribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.ManagedType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;
import javax.persistence.metamodel.Type;
import javax.persistence.metamodel.Type.PersistenceType;

import org.core4j.Enumerable;
import org.core4j.Func1;
import org.core4j.Predicate1;
import org.joda.time.Instant;
import org.odata4j.core.ODataConstants;
import org.odata4j.edm.EdmAssociation;
import org.odata4j.edm.EdmAssociationEnd;
import org.odata4j.edm.EdmAssociationSet;
import org.odata4j.edm.EdmAssociationSetEnd;
import org.odata4j.edm.EdmComplexType;
import org.odata4j.edm.EdmDataServices;
import org.odata4j.edm.EdmEntityContainer;
import org.odata4j.edm.EdmEntitySet;
import org.odata4j.edm.EdmEntityType;
import org.odata4j.edm.EdmMultiplicity;
import org.odata4j.edm.EdmNavigationProperty;
import org.odata4j.edm.EdmProperty;
import org.odata4j.edm.EdmSchema;
import org.odata4j.edm.EdmType;
import org.odata4j.producer.jpa.eclipselink.EclipseLink;

public class JPAEdmGenerator {

    public static EdmType toEdmType(SingularAttribute<?, ?> sa) {

        
        Class<?> javaType = sa.getType().getJavaType();

        if (javaType.equals(String.class))
            return EdmType.STRING;
        if (javaType.equals(BigDecimal.class))
            return EdmType.DECIMAL;
        if (javaType.equals(new byte[0].getClass()))
            return EdmType.BINARY;
        if (javaType.equals(Short.class) || javaType.equals(Short.TYPE))
            return EdmType.INT16;
        if (javaType.equals(Integer.class) || javaType.equals(Integer.TYPE))
            return EdmType.INT32;
        if (javaType.equals(Long.class) || javaType.equals(Long.TYPE))
        	return EdmType.INT64;
        if (javaType.equals(Boolean.class) || javaType.equals(Boolean.TYPE))
            return EdmType.BOOLEAN;
        if (javaType.equals(Double.class) || javaType.equals(Double.TYPE))
            return EdmType.DOUBLE;
        if (javaType.equals(Date.class))
            return EdmType.DATETIME;
        if (javaType.equals(Instant.class))
            return EdmType.DATETIME;
       

        throw new UnsupportedOperationException(javaType.toString());
    }

    private static EdmProperty toEdmProperty(String modelNamespace, SingularAttribute<?, ?> sa) {
        String name = sa.getName();
        EdmType type;
        if (sa.getPersistentAttributeType() == PersistentAttributeType.EMBEDDED){
            String simpleName = sa.getJavaType().getSimpleName();
            type = EdmType.get(modelNamespace + "." + simpleName);
        } else if (sa.getBindableJavaType().isEnum()){
            // TODO assume string mapping for now, @Enumerated info not avail in metamodel?
            type = EdmType.STRING;
        } else {
            type = toEdmType(sa);
        }
        boolean nullable = sa.isOptional();
        Integer maxLength = null;

        Map<String, Object> eclipseLinkProps = EclipseLink.getPropertyInfo(sa, type);
        if (eclipseLinkProps.containsKey("MaxLength"))
            maxLength = (Integer) eclipseLinkProps.get("MaxLength");

        return new EdmProperty(name, type, nullable, maxLength, null, null, null, null, null, null, null, null);
    }

    private static List<EdmProperty> getProperties(String modelNamespace, ManagedType<?> et){
        List<EdmProperty> properties = new ArrayList<EdmProperty>();
        for(Attribute<?, ?> att : et.getAttributes()) {

            if (att.isCollection()) {

            } else {
                SingularAttribute<?, ?> sa = (SingularAttribute<?, ?>) att;

                Type<?> type = sa.getType();
                if (type.getPersistenceType().equals(PersistenceType.BASIC) || type.getPersistenceType().equals(PersistenceType.EMBEDDABLE) ) {
                    EdmProperty prop = toEdmProperty(modelNamespace, sa);
                    properties.add(prop);
                } 
            }
        }
        return properties;
    }
    
    public static EdmDataServices buildEdm(EntityManagerFactory emf, String namespace) {

        String modelNamespace = namespace + "Model";

        List<EdmEntityType> edmEntityTypes = new ArrayList<EdmEntityType>();
        List<EdmComplexType> edmComplexTypes = new ArrayList<EdmComplexType>();
        List<EdmAssociation> associations = new ArrayList<EdmAssociation>();

        List<EdmEntitySet> entitySets = new ArrayList<EdmEntitySet>();
        List<EdmAssociationSet> associationSets = new ArrayList<EdmAssociationSet>();

        Metamodel mm = emf.getMetamodel();

        
        // complex types
        for(EmbeddableType<?> et : mm.getEmbeddables()){
 
            String name = et.getJavaType().getSimpleName();
            List<EdmProperty> properties = getProperties(modelNamespace, et);
            
            EdmComplexType ect = new EdmComplexType(modelNamespace, name, properties);
            edmComplexTypes.add(ect);
        }
        
        // entities

        for(EntityType<?> et : mm.getEntities()) {
           
            SingularAttribute<?, ?> idAttribute = et.getId(null);

            String name = et.getName();
            String key = idAttribute.getName();
            
            List<EdmProperty> properties = getProperties(modelNamespace, et);
            List<EdmNavigationProperty> navigationProperties = new ArrayList<EdmNavigationProperty>();
           
            EdmEntityType eet = new EdmEntityType(modelNamespace, null, name, null, Enumerable.create(key).toList(), properties, navigationProperties);
            edmEntityTypes.add(eet);

            EdmEntitySet ees = new EdmEntitySet(name, eet);
            entitySets.add(ees);

        }

        Map<String, EdmEntityType> eetsByName = Enumerable.create(edmEntityTypes).toMap(new Func1<EdmEntityType, String>() {
            public String apply(EdmEntityType input) {
                return input.name;
            }
        });
        Map<String, EdmEntitySet> eesByName = Enumerable.create(entitySets).toMap(new Func1<EdmEntitySet, String>() {
            public String apply(EdmEntitySet input) {
                return input.name;
            }
        });

        // associations + navigationproperties on one side

        for(EntityType<?> et2 : mm.getEntities()) {

            for(Attribute<?, ?> att : et2.getAttributes()) {

                if (att.isCollection()) {

                    // /CollectionAttribute<?,?> ca = (CollectionAttribute<?,?>)att;

                } else {
                    SingularAttribute<?, ?> sa = (SingularAttribute<?, ?>) att;

                    Type<?> type = sa.getType();
                    if (type.getPersistenceType().equals(PersistenceType.ENTITY)) {

                        EntityType<?> aet = (EntityType<?>) type;

                        EdmEntityType eet1 = eetsByName.get(et2.getName());
                        EdmEntityType eet2 = eetsByName.get(aet.getName());
                        EdmMultiplicity m1 = EdmMultiplicity.MANY;
                        EdmMultiplicity m2 = EdmMultiplicity.ZERO_TO_ONE;

                        String assocName = String.format("FK_%s_%s", eet1.name, eet2.name);
                        EdmAssociationEnd assocEnd1 = new EdmAssociationEnd(eet1.name, eet1, m1);
                        String assocEnd2Name = eet2.name;
                        if (assocEnd2Name.equals(eet1.name))
                            assocEnd2Name = assocEnd2Name + "1";
                        EdmAssociationEnd assocEnd2 = new EdmAssociationEnd(assocEnd2Name, eet2, m2);
                        EdmAssociation assoc = new EdmAssociation(modelNamespace, null, assocName, assocEnd1, assocEnd2);

                        associations.add(assoc);

                        EdmEntitySet ees1 = eesByName.get(eet1.name);
                        EdmEntitySet ees2 = eesByName.get(eet2.name);
                        EdmAssociationSet eas = new EdmAssociationSet(assocName, assoc, new EdmAssociationSetEnd(assocEnd1, ees1), new EdmAssociationSetEnd(assocEnd2, ees2));

                        associationSets.add(eas);

                        EdmNavigationProperty np = new EdmNavigationProperty(sa.getName(), assoc, assoc.end1, assoc.end2);

                        eet1.navigationProperties.add(np);

                    }

                }

            }

        }

        // navigation properties for the collection side of associations

        for(EntityType<?> et3 : mm.getEntities()) {

            for(Attribute<?, ?> att : et3.getAttributes()) {

                if (att.isCollection()) {

                    CollectionAttribute<?, ?> ca = (CollectionAttribute<?, ?>) att;

                    EntityType<?> cat = (EntityType<?>) ca.getElementType();

                    final EdmEntityType eet1 = eetsByName.get(cat.getName());
                    final EdmEntityType eet2 = eetsByName.get(et3.getName());

                    EdmAssociation assoc = Enumerable.create(associations).first(new Predicate1<EdmAssociation>() {
                        public boolean apply(EdmAssociation input) {
                            return input.end1.type.equals(eet1) && input.end2.type.equals(eet2);
                        }
                    });

                    EdmNavigationProperty np = new EdmNavigationProperty(ca.getName(), assoc, assoc.end2, assoc.end1);

                    eet2.navigationProperties.add(np);
                }

            }

        }

        EdmEntityContainer container = new EdmEntityContainer(namespace + "Entities", true, null, entitySets, associationSets,null);

        EdmSchema modelSchema = new EdmSchema(modelNamespace, null, edmEntityTypes, edmComplexTypes, associations, null);
        EdmSchema containerSchema = new EdmSchema(namespace + "Container", null, null, null, null, Enumerable.create(container).toList());

        EdmDataServices services = new EdmDataServices(ODataConstants.DATA_SERVICE_VERSION,Enumerable.create(modelSchema, containerSchema).toList());
        return services;
    }

}