package com.mfstechnologies.mymobi.controller;

import com.mfstechnologies.mymobi.model.StoredDocument;
import com.mfstechnologies.mymobi.session.DocumentStore;
import com.mfstechnologies.mymobi.validation.HtmlEscaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/documents")
public class DocumentViewerController {

    private static final Logger log = LoggerFactory.getLogger(DocumentViewerController.class);

    private final DocumentStore documentStore;

    public DocumentViewerController(DocumentStore documentStore) {
        this.documentStore = documentStore;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> showUpnForm(@PathVariable String token) {
        Optional<StoredDocument> document = documentStore.findValid(token);
        if (document.isEmpty()) {
            return linkInvalidResponse();
        }

        return ResponseEntity.ok(upnFormHtml(token, null));
    }

    @PostMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> verifyUpnAndShowDocument(
            @PathVariable String token,
            @RequestParam(required = false) String upn
    ) {
        Optional<StoredDocument> documentOpt = documentStore.findValid(token);
        if (documentOpt.isEmpty()) {
            return linkInvalidResponse();
        }
        StoredDocument document = documentOpt.get();

        if (upn == null || !upn.equals(document.getUpn())) {
            boolean nowInvalidated = documentStore.recordFailedAttempt(token);
            log.warn("document_upn_verification_failed token={} invalidated={}", token, nowInvalidated);

            if (nowInvalidated) {
                return linkInvalidResponse();
            }
            return ResponseEntity.ok(upnFormHtml(token, "Incorrect UPN. Please try again."));
        }

        log.info("document_viewed docType={} token={}", document.getDocType(), token);
        return ResponseEntity.ok(document.getHtml());
    }

    private ResponseEntity<String> linkInvalidResponse() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Link Invalid</title>
                <style>body { font-family: Arial, sans-serif; max-width: 500px; margin: 60px auto; padding: 20px; text-align: center; color: #444; }</style>
                </head>
                <body>
                <h2>This link is no longer valid</h2>
                <p>It may have expired, already been used too many times, or does not exist. Please request a new document from WhatsApp.</p>
                </body>
                </html>
                """;
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private String upnFormHtml(String token, String errorMessage) {
        String errorBlock = errorMessage != null
                ? "<p style=\"color:#c0392b;\">" + HtmlEscaper.escape(errorMessage) + "</p>"
                : "";

        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Verify Your Identity</title>
                <style>
                body { font-family: Arial, sans-serif; max-width: 400px; margin: 60px auto; padding: 20px; }
                input { width: 100%%; padding: 10px; margin-top: 10px; box-sizing: border-box; }
                button { width: 100%%; padding: 10px; margin-top: 15px; background: #2d6cdf; color: white; border: none; border-radius: 4px; cursor: pointer; }
                </style>
                </head>
                <body>
                <h2>Verify Your Identity</h2>
                <p>Please enter your UPN to view this document.</p>
                %s
                <form method="POST" action="/documents/%s">
                <input type="text" name="upn" placeholder="Enter your UPN" required>
                <button type="submit">View Document</button>
                </form>
                </body>
                </html>
                """.formatted(errorBlock, token);
    }
}