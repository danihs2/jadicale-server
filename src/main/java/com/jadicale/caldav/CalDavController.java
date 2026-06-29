package com.jadicale.caldav;

import com.jadicale.storage.CalendarStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class CalDavController {

    private static final String DAV_HEADER = "1, 2, 3, calendar-access";
    private static final String ALLOW_HEADER =
            "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, REPORT";

    @Autowired
    private CalendarStorage storage;

    @RequestMapping("/caldav/**")
    public ResponseEntity<byte[]> handle(HttpServletRequest request, Authentication auth)
            throws IOException {

        String method = request.getMethod().toUpperCase();
        String uri = request.getRequestURI();
        String username = auth.getName();

        return switch (method) {
            case "OPTIONS"  -> handleOptions();
            case "PROPFIND" -> handlePropfind(uri, username, request);
            case "MKCOL"    -> handleMkcol(uri, username);
            case "PUT"      -> handlePut(uri, username, request);
            case "GET"      -> handleGet(uri, username);
            case "DELETE"   -> handleDelete(uri, username);
            default         -> ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        };
    }

    // ── OPTIONS ────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleOptions() {
        HttpHeaders h = new HttpHeaders();
        h.add("DAV", DAV_HEADER);
        h.add("Allow", ALLOW_HEADER);
        h.add("MS-Author-Via", "DAV");
        return ResponseEntity.ok().headers(h).build();
    }

    // ── PROPFIND ───────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handlePropfind(String uri, String username,
            HttpServletRequest request) throws IOException {

        String depth = request.getHeader("Depth");
        if (depth == null) depth = "1";

        PathInfo p = parsePath(uri);
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<D:multistatus xmlns:D=\"DAV:\""
                + " xmlns:C=\"urn:ietf:params:xml:ns:caldav\""
                + " xmlns:CS=\"http://calendarserver.org/ns/\">\n");

        if (p.calendar == null) {
            // Principal / calendar-home
            String homeHref = "/caldav/" + username + "/";
            xml.append(principalResponse(homeHref, username));
            if ("1".equals(depth)) {
                for (String cal : storage.listCalendars(username)) {
                    xml.append(calendarResponse("/caldav/" + username + "/" + cal + "/", cal));
                }
            }
        } else if (p.filename == null) {
            // Calendar collection
            String calHref = "/caldav/" + username + "/" + p.calendar + "/";
            xml.append(calendarResponse(calHref, p.calendar));
            if ("1".equals(depth)) {
                for (String ev : storage.listEvents(username, p.calendar)) {
                    String etag = storage.etag(username, p.calendar, ev);
                    xml.append(eventResponse("/caldav/" + username + "/" + p.calendar + "/" + ev, etag));
                }
            }
        } else {
            // Single event
            String etag = storage.etag(username, p.calendar, p.filename);
            xml.append(eventResponse(uri, etag));
        }

        xml.append("</D:multistatus>");

        HttpHeaders h = new HttpHeaders();
        h.add("DAV", DAV_HEADER);
        h.setContentType(MediaType.APPLICATION_XML);
        return ResponseEntity.status(207)
                .headers(h)
                .body(xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ── MKCOL ─────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleMkcol(String uri, String username) throws IOException {
        PathInfo p = parsePath(uri);
        if (p.calendar == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (storage.calendarExists(username, p.calendar))
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        storage.createCalendar(username, p.calendar);
        HttpHeaders h = new HttpHeaders();
        h.add("DAV", DAV_HEADER);
        return ResponseEntity.status(HttpStatus.CREATED).headers(h).build();
    }

    // ── PUT ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handlePut(String uri, String username,
            HttpServletRequest request) throws IOException {

        PathInfo p = parsePath(uri);
        if (p.calendar == null || p.filename == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

        byte[] body = request.getInputStream().readAllBytes();
        boolean created = !storage.eventExists(username, p.calendar, p.filename);
        storage.saveEvent(username, p.calendar, p.filename, body);
        String etag = storage.etag(username, p.calendar, p.filename);

        HttpHeaders h = new HttpHeaders();
        h.setETag("\"" + etag + "\"");
        h.add("DAV", DAV_HEADER);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.NO_CONTENT)
                .headers(h).build();
    }

    // ── GET ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleGet(String uri, String username) throws IOException {
        PathInfo p = parsePath(uri);
        if (p.filename == null) return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        if (!storage.eventExists(username, p.calendar, p.filename))
            return ResponseEntity.notFound().build();

        byte[] content = storage.loadEvent(username, p.calendar, p.filename);
        String etag = storage.etag(username, p.calendar, p.filename);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/calendar; charset=utf-8"));
        h.setETag("\"" + etag + "\"");
        return ResponseEntity.ok().headers(h).body(content);
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleDelete(String uri, String username) throws IOException {
        PathInfo p = parsePath(uri);
        if (p.filename == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        storage.deleteEvent(username, p.calendar, p.filename);
        return ResponseEntity.noContent().build();
    }

    // ── XML builders ──────────────────────────────────────────────────────

    private String principalResponse(String href, String username) {
        return "<D:response>\n"
                + "  <D:href>" + href + "</D:href>\n"
                + "  <D:propstat>\n"
                + "    <D:prop>\n"
                + "      <D:resourcetype><D:collection/><D:principal/></D:resourcetype>\n"
                + "      <D:displayname>" + escapeXml(username) + "</D:displayname>\n"
                + "      <C:calendar-home-set><D:href>" + href + "</D:href></C:calendar-home-set>\n"
                + "      <C:calendar-user-address-set>"
                + "<D:href>mailto:" + escapeXml(username) + "@jadicale.local</D:href>"
                + "</C:calendar-user-address-set>\n"
                + "      <D:current-user-principal><D:href>" + href + "</D:href></D:current-user-principal>\n"
                + "    </D:prop>\n"
                + "    <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n"
                + "</D:response>\n";
    }

    private String calendarResponse(String href, String displayName) {
        return "<D:response>\n"
                + "  <D:href>" + href + "</D:href>\n"
                + "  <D:propstat>\n"
                + "    <D:prop>\n"
                + "      <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>\n"
                + "      <D:displayname>" + escapeXml(displayName) + "</D:displayname>\n"
                + "      <CS:getctag>\"" + System.currentTimeMillis() + "\"</CS:getctag>\n"
                + "      <D:sync-token>0</D:sync-token>\n"
                + "    </D:prop>\n"
                + "    <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n"
                + "</D:response>\n";
    }

    private String eventResponse(String href, String etag) {
        return "<D:response>\n"
                + "  <D:href>" + href + "</D:href>\n"
                + "  <D:propstat>\n"
                + "    <D:prop>\n"
                + "      <D:resourcetype/>\n"
                + "      <D:getcontenttype>text/calendar; charset=utf-8</D:getcontenttype>\n"
                + "      <D:getetag>\"" + etag + "\"</D:getetag>\n"
                + "    </D:prop>\n"
                + "    <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n"
                + "</D:response>\n";
    }

    // ── Path parser ───────────────────────────────────────────────────────

    private PathInfo parsePath(String uri) {
        // Strip /caldav/ prefix and trailing slash, then split
        String stripped = uri.replaceFirst("^/caldav/", "").replaceAll("/$", "");
        String[] parts = stripped.isEmpty() ? new String[0] : stripped.split("/", 3);
        PathInfo p = new PathInfo();
        if (parts.length >= 2) p.calendar = parts[1];
        if (parts.length >= 3) p.filename = parts[2];
        return p;
    }

    private static class PathInfo {
        String calendar;
        String filename;
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
