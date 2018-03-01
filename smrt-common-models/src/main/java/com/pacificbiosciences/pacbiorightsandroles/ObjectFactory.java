//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: XXX
//


package com.pacificbiosciences.pacbiorightsandroles;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlIDREF;
import javax.xml.bind.annotation.XmlRegistry;
import javax.xml.namespace.QName;


/**
 * This object contains factory methods for each 
 * Java content interface and Java element interface 
 * generated in the com.pacificbiosciences.pacbiorightsandroles package. 
 * <p>An ObjectFactory allows you to programatically 
 * construct new instances of the Java representation 
 * for XML content. The Java representation of XML 
 * content can consist of schema derived interfaces 
 * and classes representing the binding of schema 
 * type definitions, element declarations and model 
 * groups.  Factory methods for each of these are 
 * provided in this class.
 * 
 */
@XmlRegistry
public class ObjectFactory {

    private final static QName _UserIdentity_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "UserIdentity");
    private final static QName _Project_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "Project");
    private final static QName _AuditableEvent_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "AuditableEvent");
    private final static QName _UserIdentityReferencesUserIdentityReference_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "UserIdentityReference");
    private final static QName _AuditableEventReferencesAuditableEventReference_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "AuditableEventReference");
    private final static QName _RoleReferencesRoleReference_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "RoleReference");
    private final static QName _AccessRightReferencesAccessRightReference_QNAME = new QName("http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", "AccessRightReference");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: com.pacificbiosciences.pacbiorightsandroles
     * 
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link UserIdentityReferences }
     * 
     */
    public UserIdentityReferences createUserIdentityReferences() {
        return new UserIdentityReferences();
    }

    /**
     * Create an instance of {@link UserIdentityType }
     * 
     */
    public UserIdentityType createUserIdentityType() {
        return new UserIdentityType();
    }

    /**
     * Create an instance of {@link ProjectReferences }
     * 
     */
    public ProjectReferences createProjectReferences() {
        return new ProjectReferences();
    }

    /**
     * Create an instance of {@link ProjectReferenceType }
     * 
     */
    public ProjectReferenceType createProjectReferenceType() {
        return new ProjectReferenceType();
    }

    /**
     * Create an instance of {@link AuditableEvents }
     * 
     */
    public AuditableEvents createAuditableEvents() {
        return new AuditableEvents();
    }

    /**
     * Create an instance of {@link AuditableEventType }
     * 
     */
    public AuditableEventType createAuditableEventType() {
        return new AuditableEventType();
    }

    /**
     * Create an instance of {@link UserPassword }
     * 
     */
    public UserPassword createUserPassword() {
        return new UserPassword();
    }

    /**
     * Create an instance of {@link UserIdentities }
     * 
     */
    public UserIdentities createUserIdentities() {
        return new UserIdentities();
    }

    /**
     * Create an instance of {@link Projects }
     * 
     */
    public Projects createProjects() {
        return new Projects();
    }

    /**
     * Create an instance of {@link AccessRightType }
     * 
     */
    public AccessRightType createAccessRightType() {
        return new AccessRightType();
    }

    /**
     * Create an instance of {@link Roles }
     * 
     */
    public Roles createRoles() {
        return new Roles();
    }

    /**
     * Create an instance of {@link Role }
     * 
     */
    public Role createRole() {
        return new Role();
    }

    /**
     * Create an instance of {@link RoleType }
     * 
     */
    public RoleType createRoleType() {
        return new RoleType();
    }

    /**
     * Create an instance of {@link AccessRightReferences }
     * 
     */
    public AccessRightReferences createAccessRightReferences() {
        return new AccessRightReferences();
    }

    /**
     * Create an instance of {@link AuditableEventReferences }
     * 
     */
    public AuditableEventReferences createAuditableEventReferences() {
        return new AuditableEventReferences();
    }

    /**
     * Create an instance of {@link AccessRights }
     * 
     */
    public AccessRights createAccessRights() {
        return new AccessRights();
    }

    /**
     * Create an instance of {@link AccessRight }
     * 
     */
    public AccessRight createAccessRight() {
        return new AccessRight();
    }

    /**
     * Create an instance of {@link RoleReferences }
     * 
     */
    public RoleReferences createRoleReferences() {
        return new RoleReferences();
    }

    /**
     * Create an instance of {@link RnR }
     * 
     */
    public RnR createRnR() {
        return new RnR();
    }

    /**
     * Create an instance of {@link Person }
     * 
     */
    public Person createPerson() {
        return new Person();
    }

    /**
     * Create an instance of {@link EventToken }
     * 
     */
    public EventToken createEventToken() {
        return new EventToken();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link UserIdentityType }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "UserIdentity")
    public JAXBElement<UserIdentityType> createUserIdentity(UserIdentityType value) {
        return new JAXBElement<UserIdentityType>(_UserIdentity_QNAME, UserIdentityType.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AccessRightType }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "Project")
    public JAXBElement<AccessRightType> createProject(AccessRightType value) {
        return new JAXBElement<AccessRightType>(_Project_QNAME, AccessRightType.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AuditableEventType }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "AuditableEvent")
    public JAXBElement<AuditableEventType> createAuditableEvent(AuditableEventType value) {
        return new JAXBElement<AuditableEventType>(_AuditableEvent_QNAME, AuditableEventType.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Object }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "UserIdentityReference", scope = UserIdentityReferences.class)
    @XmlIDREF
    public JAXBElement<Object> createUserIdentityReferencesUserIdentityReference(Object value) {
        return new JAXBElement<Object>(_UserIdentityReferencesUserIdentityReference_QNAME, Object.class, UserIdentityReferences.class, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Object }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "AuditableEventReference", scope = AuditableEventReferences.class)
    @XmlIDREF
    public JAXBElement<Object> createAuditableEventReferencesAuditableEventReference(Object value) {
        return new JAXBElement<Object>(_AuditableEventReferencesAuditableEventReference_QNAME, Object.class, AuditableEventReferences.class, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Object }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "RoleReference", scope = RoleReferences.class)
    @XmlIDREF
    public JAXBElement<Object> createRoleReferencesRoleReference(Object value) {
        return new JAXBElement<Object>(_RoleReferencesRoleReference_QNAME, Object.class, RoleReferences.class, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link Object }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://pacificbiosciences.com/PacBioRightsAndRoles.xsd", name = "AccessRightReference", scope = AccessRightReferences.class)
    @XmlIDREF
    public JAXBElement<Object> createAccessRightReferencesAccessRightReference(Object value) {
        return new JAXBElement<Object>(_AccessRightReferencesAccessRightReference_QNAME, Object.class, AccessRightReferences.class, value);
    }

}
