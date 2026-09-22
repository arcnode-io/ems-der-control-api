package io.arcnode.dercontrol.mirror;

import io.arcnode.dercontrol.mirror.ieee20305.MirrorUsagePointElement;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.springframework.core.io.ClassPathResource;
import org.xml.sax.SAXException;

/**
 * Marshals a {@link MirrorUsagePointElement} to real IEEE 2030.5 XML via JAXB classes generated
 * directly from {@code src/main/resources/xsd/sep.xsd}, and validates the result against that same
 * schema — producing schema-invalid XML is a compile error (wrong generated type) or a caught
 * validation failure here, never a silent wire-format mistake.
 */
public final class Ieee20305Xml {

  private static final String SCHEMA_RESOURCE = "xsd/sep.xsd";

  private Ieee20305Xml() {}

  /** Marshals a {@code MirrorUsagePointElement} as the XML document's root element. */
  public static String marshal(MirrorUsagePointElement usagePoint) {
    try {
      JAXBContext context = JAXBContext.newInstance(MirrorUsagePointElement.class);
      Marshaller marshaller = context.createMarshaller();
      StringWriter writer = new StringWriter();
      marshaller.marshal(usagePoint, writer);
      return writer.toString();
    } catch (JAXBException e) {
      throw new IllegalStateException("failed to marshal MirrorUsagePoint", e);
    }
  }

  /**
   * @throws org.xml.sax.SAXException if {@code xml} does not validate against the real IEEE 2030.5
   *     schema
   */
  public static void validate(String xml) throws SAXException {
    Schema schema = loadSchema();
    Validator validator = schema.newValidator();
    try {
      validator.validate(new StreamSource(new java.io.StringReader(xml)));
    } catch (java.io.IOException e) {
      // Reason: a StringReader-backed Source cannot actually fail with an I/O error; the checked
      // signature is Validator's, not a real failure mode here.
      throw new UncheckedIOException(e);
    }
  }

  private static Schema loadSchema() {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      return factory.newSchema(
          new StreamSource(new ClassPathResource(SCHEMA_RESOURCE).getInputStream()));
    } catch (SAXException e) {
      throw new IllegalStateException("failed to compile " + SCHEMA_RESOURCE, e);
    } catch (java.io.IOException e) {
      throw new UncheckedIOException("failed to read " + SCHEMA_RESOURCE, e);
    }
  }
}
