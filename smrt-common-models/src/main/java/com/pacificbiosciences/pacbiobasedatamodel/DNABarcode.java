//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.11 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2016.12.19 at 11:50:03 AM PST 
//


package com.pacificbiosciences.pacbiobasedatamodel;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;


/**
 * Composite of uuid, sequence, and name
 * 
 * <p>Java class for DNABarcode complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="DNABarcode"&gt;
 *   &lt;complexContent&gt;
 *     &lt;extension base="{http://pacificbiosciences.com/PacBioBaseDataModel.xsd}BaseEntityType"&gt;
 *       &lt;attribute name="DNASequence" type="{http://www.w3.org/2001/XMLSchema}anySimpleType" /&gt;
 *     &lt;/extension&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "DNABarcode")
public class DNABarcode
    extends BaseEntityType
{

    @XmlAttribute(name = "DNASequence")
    @XmlSchemaType(name = "anySimpleType")
    protected String dnaSequence;

    /**
     * Gets the value of the dnaSequence property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getDNASequence() {
        return dnaSequence;
    }

    /**
     * Sets the value of the dnaSequence property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setDNASequence(String value) {
        this.dnaSequence = value;
    }

}
