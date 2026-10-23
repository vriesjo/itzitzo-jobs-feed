package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public class RssRenderer {
    private final String title;
    private final String description;
    private final String websiteUrl;
    private final String feedUrl;

    public RssRenderer(String title, String description, String websiteUrl, String feedUrl) {
        this.title = title;
        this.description = description;
        this.websiteUrl = websiteUrl;
        this.feedUrl = feedUrl;
    }

    public String render(List<Job> jobs) {
        try {
            StringWriter output = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", "2.0");
            xml.writeNamespace("atom", "http://www.w3.org/2005/Atom");
            xml.writeStartElement("channel");
            element(xml, "title", title);
            element(xml, "link", websiteUrl);
            element(xml, "description", description);
            xml.writeStartElement("atom", "link", "http://www.w3.org/2005/Atom");
            xml.writeAttribute("href", feedUrl);
            xml.writeAttribute("rel", "self");
            xml.writeAttribute("type", "application/rss+xml");
            xml.writeEndElement();
            for (Job job : jobs) {
                xml.writeStartElement("item");
                element(xml, "title", job.details().title());
                element(xml, "description", job.details().description());
                if (job.details().applicationUrl() != null) {
                    element(xml, "link", job.details().applicationUrl());
                }
                xml.writeStartElement("guid");
                xml.writeAttribute("isPermaLink", "false");
                xml.writeCharacters("urn:itzitzo:job:" + job.id());
                xml.writeEndElement();
                Instant publishedAt = job.details().publishedAt() == null
                    ? job.receivedAt() : job.details().publishedAt();
                element(xml, "pubDate", DateTimeFormatter.RFC_1123_DATE_TIME.format(publishedAt.atZone(ZoneOffset.UTC)));
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return output.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Unable to render RSS", e);
        }
    }

    private static void element(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeStartElement(name);
        StringBuilder safe = new StringBuilder();
        value.codePoints().forEach(code -> safe.appendCodePoint(
            code == 0x9 || code == 0xA || code == 0xD || (code >= 0x20 && code <= 0xD7FF)
                || (code >= 0xE000 && code <= 0xFFFD) || (code >= 0x10000 && code <= 0x10FFFF)
                ? code : 0xFFFD));
        xml.writeCharacters(safe.toString());
        xml.writeEndElement();
    }
}
