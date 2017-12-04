//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2017.12.04 at 02:50:32 PM PST 
//


package com.pacificbiosciences.pacbiopartnumbers;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for PartTypes.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="PartTypes">
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}string">
 *     &lt;enumeration value="WFA"/>
 *     &lt;enumeration value="BDK"/>
 *     &lt;enumeration value="TPK"/>
 *     &lt;enumeration value="SQK"/>
 *     &lt;enumeration value="CCK"/>
 *     &lt;enumeration value="CPK"/>
 *     &lt;enumeration value="OSE"/>
 *     &lt;enumeration value="CMO"/>
 *     &lt;enumeration value="PRK"/>
 *     &lt;enumeration value="Other"/>
 *   &lt;/restriction>
 * &lt;/simpleType>
 * </pre>
 * 
 */
@XmlType(name = "PartTypes")
@XmlEnum
public enum PartTypes {

    WFA("WFA"),
    BDK("BDK"),
    TPK("TPK"),
    SQK("SQK"),
    CCK("CCK"),
    CPK("CPK"),
    OSE("OSE"),
    CMO("CMO"),
    PRK("PRK"),
    @XmlEnumValue("Other")
    OTHER("Other");
    private final String value;

    PartTypes(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static PartTypes fromValue(String v) {
        for (PartTypes c: PartTypes.values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }

}
