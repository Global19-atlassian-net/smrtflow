//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2017.05.17 at 02:46:42 PM PDT 
//


package com.pacificbiosciences.pacbiocollectionmetadata;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import com.pacificbiosciences.pacbiobasedatamodel.DataEntityType;


/**
 * <p>Java class for anonymous complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="AutomationName" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="AutomationParameters" minOccurs="0">
 *           &lt;complexType>
 *             &lt;complexContent>
 *               &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *                 &lt;sequence>
 *                   &lt;element name="AutomationParameter" type="{http://pacificbiosciences.com/PacBioBaseDataModel.xsd}DataEntityType" maxOccurs="unbounded" minOccurs="0"/>
 *                 &lt;/sequence>
 *               &lt;/restriction>
 *             &lt;/complexContent>
 *           &lt;/complexType>
 *         &lt;/element>
 *         &lt;element name="CellCountInJob" type="{http://www.w3.org/2001/XMLSchema}int"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
    "automationName",
    "automationParameters",
    "cellCountInJob"
})
@XmlRootElement(name = "Secondary")
public class Secondary {

    @XmlElement(name = "AutomationName", required = true)
    protected String automationName;
    @XmlElement(name = "AutomationParameters")
    protected Secondary.AutomationParameters automationParameters;
    @XmlElement(name = "CellCountInJob")
    protected int cellCountInJob;

    /**
     * Gets the value of the automationName property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getAutomationName() {
        return automationName;
    }

    /**
     * Sets the value of the automationName property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setAutomationName(String value) {
        this.automationName = value;
    }

    /**
     * Gets the value of the automationParameters property.
     * 
     * @return
     *     possible object is
     *     {@link Secondary.AutomationParameters }
     *     
     */
    public Secondary.AutomationParameters getAutomationParameters() {
        return automationParameters;
    }

    /**
     * Sets the value of the automationParameters property.
     * 
     * @param value
     *     allowed object is
     *     {@link Secondary.AutomationParameters }
     *     
     */
    public void setAutomationParameters(Secondary.AutomationParameters value) {
        this.automationParameters = value;
    }

    /**
     * Gets the value of the cellCountInJob property.
     * 
     */
    public int getCellCountInJob() {
        return cellCountInJob;
    }

    /**
     * Sets the value of the cellCountInJob property.
     * 
     */
    public void setCellCountInJob(int value) {
        this.cellCountInJob = value;
    }


    /**
     * <p>Java class for anonymous complex type.
     * 
     * <p>The following schema fragment specifies the expected content contained within this class.
     * 
     * <pre>
     * &lt;complexType>
     *   &lt;complexContent>
     *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
     *       &lt;sequence>
     *         &lt;element name="AutomationParameter" type="{http://pacificbiosciences.com/PacBioBaseDataModel.xsd}DataEntityType" maxOccurs="unbounded" minOccurs="0"/>
     *       &lt;/sequence>
     *     &lt;/restriction>
     *   &lt;/complexContent>
     * &lt;/complexType>
     * </pre>
     * 
     * 
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "", propOrder = {
        "automationParameter"
    })
    public static class AutomationParameters {

        @XmlElement(name = "AutomationParameter")
        protected List<DataEntityType> automationParameter;

        /**
         * Gets the value of the automationParameter property.
         * 
         * <p>
         * This accessor method returns a reference to the live list,
         * not a snapshot. Therefore any modification you make to the
         * returned list will be present inside the JAXB object.
         * This is why there is not a <CODE>set</CODE> method for the automationParameter property.
         * 
         * <p>
         * For example, to add a new item, do as follows:
         * <pre>
         *    getAutomationParameter().add(newItem);
         * </pre>
         * 
         * 
         * <p>
         * Objects of the following type(s) are allowed in the list
         * {@link DataEntityType }
         * 
         * 
         */
        public List<DataEntityType> getAutomationParameter() {
            if (automationParameter == null) {
                automationParameter = new ArrayList<DataEntityType>();
            }
            return this.automationParameter;
        }

    }

}
