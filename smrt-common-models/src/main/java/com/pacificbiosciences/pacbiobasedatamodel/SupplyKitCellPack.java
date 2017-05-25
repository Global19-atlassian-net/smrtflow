//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2017.05.17 at 02:46:42 PM PDT 
//


package com.pacificbiosciences.pacbiobasedatamodel;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;


/**
 * Represents the package of cells. 
 * 
 * <p>Java class for SupplyKitCellPack complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="SupplyKitCellPack">
 *   &lt;complexContent>
 *     &lt;extension base="{http://pacificbiosciences.com/PacBioBaseDataModel.xsd}PartNumberType">
 *       &lt;sequence>
 *         &lt;element name="ChipLayout" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="SupportsCellReuse" type="{http://www.w3.org/2001/XMLSchema}anySimpleType" />
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SupplyKitCellPack", propOrder = {
    "chipLayout"
})
public class SupplyKitCellPack
    extends PartNumberType
{

    @XmlElement(name = "ChipLayout")
    protected String chipLayout;
    @XmlAttribute(name = "SupportsCellReuse")
    @XmlSchemaType(name = "anySimpleType")
    protected String supportsCellReuse;

    /**
     * Gets the value of the chipLayout property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getChipLayout() {
        return chipLayout;
    }

    /**
     * Sets the value of the chipLayout property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setChipLayout(String value) {
        this.chipLayout = value;
    }

    /**
     * Gets the value of the supportsCellReuse property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getSupportsCellReuse() {
        return supportsCellReuse;
    }

    /**
     * Sets the value of the supportsCellReuse property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setSupportsCellReuse(String value) {
        this.supportsCellReuse = value;
    }

}
