//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: XXX
//


package com.pacificbiosciences.pacbiobasedatamodel;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * A more specific template kit representation (includes SupplyKit fields). 
 * 
 * <p>Java class for SupplyKitTemplate complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="SupplyKitTemplate">
 *   &lt;complexContent>
 *     &lt;extension base="{http://pacificbiosciences.com/PacBioBaseDataModel.xsd}PartNumberType">
 *       &lt;sequence>
 *         &lt;element name="LeftAdaptorSequence" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="LeftPrimerSequence" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="RightAdaptorSequence" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *         &lt;element name="RightPrimerSequence" type="{http://www.w3.org/2001/XMLSchema}string" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="MinInsertSize" type="{http://www.w3.org/2001/XMLSchema}int" />
 *       &lt;attribute name="MaxInsertSize" type="{http://www.w3.org/2001/XMLSchema}int" />
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SupplyKitTemplate", propOrder = {
    "leftAdaptorSequence",
    "leftPrimerSequence",
    "rightAdaptorSequence",
    "rightPrimerSequence"
})
public class SupplyKitTemplate
    extends PartNumberType
{

    @XmlElement(name = "LeftAdaptorSequence")
    protected String leftAdaptorSequence;
    @XmlElement(name = "LeftPrimerSequence")
    protected String leftPrimerSequence;
    @XmlElement(name = "RightAdaptorSequence")
    protected String rightAdaptorSequence;
    @XmlElement(name = "RightPrimerSequence")
    protected String rightPrimerSequence;
    @XmlAttribute(name = "MinInsertSize")
    protected Integer minInsertSize;
    @XmlAttribute(name = "MaxInsertSize")
    protected Integer maxInsertSize;

    /**
     * Gets the value of the leftAdaptorSequence property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getLeftAdaptorSequence() {
        return leftAdaptorSequence;
    }

    /**
     * Sets the value of the leftAdaptorSequence property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setLeftAdaptorSequence(String value) {
        this.leftAdaptorSequence = value;
    }

    /**
     * Gets the value of the leftPrimerSequence property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getLeftPrimerSequence() {
        return leftPrimerSequence;
    }

    /**
     * Sets the value of the leftPrimerSequence property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setLeftPrimerSequence(String value) {
        this.leftPrimerSequence = value;
    }

    /**
     * Gets the value of the rightAdaptorSequence property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getRightAdaptorSequence() {
        return rightAdaptorSequence;
    }

    /**
     * Sets the value of the rightAdaptorSequence property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setRightAdaptorSequence(String value) {
        this.rightAdaptorSequence = value;
    }

    /**
     * Gets the value of the rightPrimerSequence property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getRightPrimerSequence() {
        return rightPrimerSequence;
    }

    /**
     * Sets the value of the rightPrimerSequence property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setRightPrimerSequence(String value) {
        this.rightPrimerSequence = value;
    }

    /**
     * Gets the value of the minInsertSize property.
     * 
     * @return
     *     possible object is
     *     {@link Integer }
     *     
     */
    public Integer getMinInsertSize() {
        return minInsertSize;
    }

    /**
     * Sets the value of the minInsertSize property.
     * 
     * @param value
     *     allowed object is
     *     {@link Integer }
     *     
     */
    public void setMinInsertSize(Integer value) {
        this.minInsertSize = value;
    }

    /**
     * Gets the value of the maxInsertSize property.
     * 
     * @return
     *     possible object is
     *     {@link Integer }
     *     
     */
    public Integer getMaxInsertSize() {
        return maxInsertSize;
    }

    /**
     * Sets the value of the maxInsertSize property.
     * 
     * @param value
     *     allowed object is
     *     {@link Integer }
     *     
     */
    public void setMaxInsertSize(Integer value) {
        this.maxInsertSize = value;
    }

}
