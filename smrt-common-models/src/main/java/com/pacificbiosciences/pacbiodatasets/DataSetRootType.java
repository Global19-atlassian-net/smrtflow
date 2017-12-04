//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2017.12.04 at 02:50:32 PM PST 
//


package com.pacificbiosciences.pacbiodatasets;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for DataSetRootType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="DataSetRootType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;choice>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}AlignmentSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}BarcodeSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}ConsensusAlignmentSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}ConsensusReadSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}ContigSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}HdfSubreadSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}ReferenceSet" minOccurs="0"/>
 *         &lt;element ref="{http://pacificbiosciences.com/PacBioDatasets.xsd}SubreadSet" minOccurs="0"/>
 *       &lt;/choice>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "DataSetRootType", propOrder = {
    "alignmentSet",
    "barcodeSet",
    "consensusAlignmentSet",
    "consensusReadSet",
    "contigSet",
    "hdfSubreadSet",
    "referenceSet",
    "subreadSet"
})
public class DataSetRootType {

    @XmlElement(name = "AlignmentSet")
    protected AlignmentSet alignmentSet;
    @XmlElement(name = "BarcodeSet")
    protected BarcodeSet barcodeSet;
    @XmlElement(name = "ConsensusAlignmentSet")
    protected ConsensusAlignmentSet consensusAlignmentSet;
    @XmlElement(name = "ConsensusReadSet")
    protected ConsensusReadSet consensusReadSet;
    @XmlElement(name = "ContigSet")
    protected ContigSet contigSet;
    @XmlElement(name = "HdfSubreadSet")
    protected HdfSubreadSet hdfSubreadSet;
    @XmlElement(name = "ReferenceSet")
    protected ReferenceSet referenceSet;
    @XmlElement(name = "SubreadSet")
    protected SubreadSet subreadSet;

    /**
     * Gets the value of the alignmentSet property.
     * 
     * @return
     *     possible object is
     *     {@link AlignmentSet }
     *     
     */
    public AlignmentSet getAlignmentSet() {
        return alignmentSet;
    }

    /**
     * Sets the value of the alignmentSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link AlignmentSet }
     *     
     */
    public void setAlignmentSet(AlignmentSet value) {
        this.alignmentSet = value;
    }

    /**
     * Gets the value of the barcodeSet property.
     * 
     * @return
     *     possible object is
     *     {@link BarcodeSet }
     *     
     */
    public BarcodeSet getBarcodeSet() {
        return barcodeSet;
    }

    /**
     * Sets the value of the barcodeSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link BarcodeSet }
     *     
     */
    public void setBarcodeSet(BarcodeSet value) {
        this.barcodeSet = value;
    }

    /**
     * Gets the value of the consensusAlignmentSet property.
     * 
     * @return
     *     possible object is
     *     {@link ConsensusAlignmentSet }
     *     
     */
    public ConsensusAlignmentSet getConsensusAlignmentSet() {
        return consensusAlignmentSet;
    }

    /**
     * Sets the value of the consensusAlignmentSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link ConsensusAlignmentSet }
     *     
     */
    public void setConsensusAlignmentSet(ConsensusAlignmentSet value) {
        this.consensusAlignmentSet = value;
    }

    /**
     * Gets the value of the consensusReadSet property.
     * 
     * @return
     *     possible object is
     *     {@link ConsensusReadSet }
     *     
     */
    public ConsensusReadSet getConsensusReadSet() {
        return consensusReadSet;
    }

    /**
     * Sets the value of the consensusReadSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link ConsensusReadSet }
     *     
     */
    public void setConsensusReadSet(ConsensusReadSet value) {
        this.consensusReadSet = value;
    }

    /**
     * Gets the value of the contigSet property.
     * 
     * @return
     *     possible object is
     *     {@link ContigSet }
     *     
     */
    public ContigSet getContigSet() {
        return contigSet;
    }

    /**
     * Sets the value of the contigSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link ContigSet }
     *     
     */
    public void setContigSet(ContigSet value) {
        this.contigSet = value;
    }

    /**
     * Gets the value of the hdfSubreadSet property.
     * 
     * @return
     *     possible object is
     *     {@link HdfSubreadSet }
     *     
     */
    public HdfSubreadSet getHdfSubreadSet() {
        return hdfSubreadSet;
    }

    /**
     * Sets the value of the hdfSubreadSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link HdfSubreadSet }
     *     
     */
    public void setHdfSubreadSet(HdfSubreadSet value) {
        this.hdfSubreadSet = value;
    }

    /**
     * Gets the value of the referenceSet property.
     * 
     * @return
     *     possible object is
     *     {@link ReferenceSet }
     *     
     */
    public ReferenceSet getReferenceSet() {
        return referenceSet;
    }

    /**
     * Sets the value of the referenceSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link ReferenceSet }
     *     
     */
    public void setReferenceSet(ReferenceSet value) {
        this.referenceSet = value;
    }

    /**
     * Gets the value of the subreadSet property.
     * 
     * @return
     *     possible object is
     *     {@link SubreadSet }
     *     
     */
    public SubreadSet getSubreadSet() {
        return subreadSet;
    }

    /**
     * Sets the value of the subreadSet property.
     * 
     * @param value
     *     allowed object is
     *     {@link SubreadSet }
     *     
     */
    public void setSubreadSet(SubreadSet value) {
        this.subreadSet = value;
    }

}
