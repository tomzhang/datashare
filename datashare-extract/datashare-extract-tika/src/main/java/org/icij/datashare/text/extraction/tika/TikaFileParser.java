package org.icij.datashare.text.extraction.tika;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.Optional;
import java.util.function.Function;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;

import org.xml.sax.SAXException;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.LinkContentHandler;
import org.apache.tika.sax.TeeContentHandler;

import com.optimaize.langdetect.*;
import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObjectFactory;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.*;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.extraction.AbstractFileParser;


/**
 * {@link AbstractFileParser}
 * {@link FileParser}
 * {@link Type#TIKA}
 *
 * Created by julien on 3/9/16.
 */
public final class TikaFileParser extends AbstractFileParser {

    public static final String RESOURCE_NAME           = Metadata.RESOURCE_NAME_KEY;
    public static final String CONTENT_TYPE            = Metadata.CONTENT_TYPE;
    public static final String CONTENT_LENGTH          = Metadata.CONTENT_LENGTH;
    public static final String CONTENT_ENCODING        = Metadata.CONTENT_ENCODING;
    public static final String CONTENT_LANGUAGE        = Metadata.CONTENT_LANGUAGE;
    public static final String CONTENT_LANGUAGE_BELIEF = "Content-Language-Belief";

    public static final Function<Language, Language> LANGUAGE_MAPPER =
            lang -> {
                if (lang.equals(ENGLISH) || lang.equals(SPANISH) || lang.equals(FRENCH) || lang.equals(GERMAN))
                    return lang;
                if (lang.equals(GALICIAN) || lang.equals(CATALAN))
                    return SPANISH;
                return ENGLISH;
            };


    private final TikaConfig config = TikaConfig.getDefaultConfig();

    private final PDFParserConfig pdfConfig = new PDFParserConfig();

    private final TesseractOCRConfig ocrConfig = new TesseractOCRConfig();

    private final Set<MediaType> excludedMediaTypes = new HashSet<>();

    private final AutoDetectParser parser;

    private final ParseContext context;

    private TeeContentHandler handler;

    private LanguageDetector languageDetector;

    private TextObjectFactory textObjectFactory;

    private Metadata metadata;


    public TikaFileParser(Properties properties) throws IOException {
        super(properties);

        if (ocrEnabled) { enableOcr(); } else { disableOcr(); }
        setOcrLanguage(language);

        parser = new AutoDetectParser(config);
        parser.setFallback(new ErrorParser(parser, excludedMediaTypes));

        context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(Parser.class, parser);

        List<LanguageProfile> languageProfiles = new LanguageProfileReader().readAllBuiltIn();
        languageDetector  = LanguageDetectorBuilder.create(NgramExtractors.standard()).withProfiles(languageProfiles).build();
        textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();
    }

    public TikaFileParser() throws IOException {
        this(new Properties());
    }


    @Override
    public Optional<Document> parse(Path filePath) {
        Metadata metadata = new Metadata();
        try (TikaInputStream input = TikaInputStream.get(filePath, metadata)) {
            return parse(input, metadata, filePath);
        } catch (IOException e) {
            LOGGER.error("Failed to get input stream from " + filePath, e);
            return Optional.empty();
        }
    }

    private Optional<Document> parse(InputStream is, Metadata metadata, Path filePath) {
        BodyContentHandler textHandler = new BodyContentHandler(-1);
        LinkContentHandler linkHandler = new LinkContentHandler();

        handler  = new TeeContentHandler(textHandler, linkHandler);
        try {
            parser.parse(is, handler, metadata, context);
        } catch (IOException | SAXException | TikaException e) {
            LOGGER.error("Failed to parse input stream", e);
            return Optional.empty();
        }

        String content = textHandler.toString();

        com.google.common.base.Optional<LdLocale> langOpt = languageDetector.detect(textObjectFactory.forText(content));
        if( langOpt.isPresent()) {
            metadata.set(CONTENT_LANGUAGE, applyLanguageMap(langOpt.get().getLanguage()));
        }

        return Document.create(
                filePath,
                content,
                getLanguage(metadata).orElse(UNKNOWN),
                getEncoding(metadata).orElse(Charset.defaultCharset()),
                getMimeType(metadata).orElse("UNKNOWN"),
                getMetadataAsMap(metadata).orElse(new HashMap<>()),
                getType()
        );
    }

    private void enableOcr() {
        pdfConfig.setExtractInlineImages(true);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);
        context.set(TesseractOCRConfig.class, ocrConfig);
        ocrEnabled = true;
    }

    private void disableOcr() {
        excludeParser(TesseractOCRParser.class);
        pdfConfig.setExtractInlineImages(false);
        ocrEnabled = false;
    }

    private void setOcrLanguage(Language language) {
        if (language != null && ! asList(NONE, UNKNOWN).contains(language)) {
            ocrConfig.setLanguage(language.toString());
        }
    }

    private String applyLanguageMap(String language) {
        Optional<Language> langOpt = Language.parse(language);
        return langOpt.isPresent() ?
                LANGUAGE_MAPPER.apply(langOpt.get()).toString() :
                language;
    }

    private void excludeParser(final Class exclude) {
        final CompositeParser                        composite = (CompositeParser) config.getParser();
        final Map<MediaType, Parser>                 parsers   = composite.getParsers();
        final Iterator<Map.Entry<MediaType, Parser>> iterator  = parsers.entrySet().iterator();
        final ParseContext                           context   = new ParseContext();
        while (iterator.hasNext()) {
            Map.Entry<MediaType, Parser> pair = iterator.next();
            Parser parser = pair.getValue();
            if (parser.getClass() == exclude) {
                iterator.remove();
                excludedMediaTypes.addAll(parser.getSupportedTypes(context));
            }
        }
        composite.setParsers(parsers);
    }

    private static Optional<Language> getLanguage(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_LANGUAGE)) {
            return Optional.empty();
        }
        return Language.parse(metadata.get(CONTENT_LANGUAGE));
    }

    private static OptionalInt getLength(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_LENGTH)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(metadata.get(CONTENT_LENGTH)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static Optional<Charset> getEncoding(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_ENCODING)) {
            return Optional.empty();
        }
        return Optional.of(StandardCharsets.UTF_8);
    }

    private static Optional<String> getName(Metadata metadata) {
        if (! asList(metadata.names()).contains(RESOURCE_NAME)) {
            return Optional.empty();
        }
        return Optional.of(metadata.get(RESOURCE_NAME));
    }

    private static Optional<String> getMimeType(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_TYPE)) {
            return Optional.empty();
        }
        return Optional.of(metadata.get(CONTENT_TYPE));
    }

    private static Optional<Map<String, String>> getMetadataAsMap(Metadata metadata) {
        Map<String, String> map = new HashMap<String, String>() {{
            stream(metadata.names())
                    .forEach( key -> put(key, metadata.get(key)) );
        }};
        return Optional.of(map);
    }

}
