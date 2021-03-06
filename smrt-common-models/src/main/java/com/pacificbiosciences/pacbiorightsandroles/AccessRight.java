//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: XXX
//


package com.pacificbiosciences.pacbiorightsandroles;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for anonymous complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;extension base="{http://pacificbiosciences.com/PacBioRightsAndRoles.xsd}AccessRightType">
 *       &lt;sequence>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioRightsAndRoles.xsd}RoleReferences"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioRightsAndRoles.xsd}AuditableEventReferences" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "roleReferences",
    "auditableEventReferences"
})
@XmlRootElement(name = "AccessRight")
public class AccessRight
    extends AccessRightType
{

    @XmlElement(name = "RoleReferences", required = true)
    protected RoleReferences roleReferences;
    @XmlElement(name = "AuditableEventReferences")
    protected AuditableEventReferences auditableEventReferences;

    /**
     * Gets the value of the roleReferences property.
     * 
     * @return
     *     possible object is
     *     {@link RoleReferences }
     *     
     */
    public RoleReferences getRoleReferences() {
        return roleReferences;
    }

    /**
     * Sets the value of the roleReferences property.
     * 
     * @param value
     *     allowed object is
     *     {@link RoleReferences }
     *     
     */
    public void setRoleReferences(RoleReferences value) {
        this.roleReferences = value;
    }

    /**
     * Gets the value of the auditableEventReferences property.
     * 
     * @return
     *     possible object is
     *     {@link AuditableEventReferences }
     *     
     */
    public AuditableEventReferences getAuditableEventReferences() {
        return auditableEventReferences;
    }

    /**
     * Sets the value of the auditableEventReferences property.
     * 
     * @param value
     *     allowed object is
     *     {@link AuditableEventReferences }
     *     
     */
    public void setAuditableEventReferences(AuditableEventReferences value) {
        this.auditableEventReferences = value;
    }

}
