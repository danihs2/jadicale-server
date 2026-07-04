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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class CalDavController {

    private static final String DAV_CAPS = "1, 2, 3, calendar-access";
    private static final String CALDAV_ALLOW =
            "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, REPORT";

    @Autowired
    private CalendarStorage storage;

    @RequestMapping("/caldav/**")
    public ResponseEntity<byte[]> handle(HttpServletRequest request, Authentication auth)
            throws IOException {
        String method   = request.getMethod().toUpperCase();
        String username = auth.getName();
        return switch (method) {
            case "OPTIONS"  -> handleOptions();
            case "PROPFIND" -> handlePropfind(username, request);
            case "REPORT"   -> handleReport(username, request);
            case "MKCOL"    -> handleMkcol(username, request);
            case "PUT"      -> handlePut(username, request);
            case "GET"      -> handleGet(username, request);
            case "DELETE"   -> handleDelete(username, request);
            default         -> ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        };
    }

    // ── OPTIONS ────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleOptions() {
        HttpHeaders h = new HttpHeaders();
        h.add("DAV", DAV_CAPS);
        h.add("Allow", CALDAV_ALLOW);
        h.add("MS-Author-Via", "DAV");
        return ResponseEntity.ok().headers(h).build();
    }

    // ── PROPFIND ───────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handlePropfind(String username, HttpServletRequest request)
            throws IOException {
        String depth = request.getHeader("Depth");
        if (depth == null) depth = "1";
        PathInfo p    = parsePath(request);
        String base   = base(request);

        StringBuilder xml = startMultistatus();

        if (p.calendar == null) {
            xml.append(principalResponse(base + username + "/", username));
            if ("1".equals(depth)) {
                for (String cal : storage.listCalendars(username)) {
                    xml.append(calendarResponse(base + username + "/" + cal + "/", cal));
                }
            }
        } else if (p.filename == null) {
            xml.append(calendarResponse(base + username + "/" + p.calendar + "/", p.calendar));
            if ("1".equals(depth)) {
                for (String ev : storage.listEvents(username, p.calendar)) {
                    xml.append(eventMetaResponse(
                            base + username + "/" + p.calendar + "/" + ev,
                            storage.etag(username, p.calendar, ev)));
                }
            }
        } else {
            if (!storage.eventExists(username, p.calendar, p.filename))
                return ResponseEntity.notFound().build();
            xml.append(eventMetaResponse(
                    base + username + "/" + p.calendar + "/" + p.filename,
                    storage.etag(username, p.calendar, p.filename)));
        }

        return multistatusResponse(xml);
    }

    // ── REPORT ─────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleReport(String username, HttpServletRequest request)
            throws IOException {
        byte[] body    = request.getInputStream().readAllBytes();
        String bodyXml = new String(body, StandardCharsets.UTF_8);
        PathInfo p     = parsePath(request);
        String base    = base(request);

        if (bodyXml.contains("calendar-multiget")) {
            return handleCalendarMultiget(username, p, base, bodyXml);
        } else {
            return handleCalendarQuery(username, p, base, bodyXml);
        }
    }

    private ResponseEntity<byte[]> handleCalendarQuery(String username, PathInfo p,
            String base, String bodyXml) throws IOException {
        if (p.calendar == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

        String rangeStart = extractAttr(bodyXml, "time-range", "start");
        String rangeEnd   = extractAttr(bodyXml, "time-range", "end");

        StringBuilder xml = startMultistatus();
        for (String ev : storage.listEvents(username, p.calendar)) {
            byte[] content = storage.loadEvent(username, p.calendar, ev);
            String ics     = new String(content, StandardCharsets.UTF_8);
            if (rangeStart != null && rangeEnd != null && !eventInRange(ics, rangeStart, rangeEnd)) {
                continue;
            }
            xml.append(eventDataResponse(
                    base + username + "/" + p.calendar + "/" + ev,
                    storage.etag(username, p.calendar, ev),
                    ics));
        }
        return multistatusResponse(xml);
    }

    private ResponseEntity<byte[]> handleCalendarMultiget(String username, PathInfo p,
            String base, String bodyXml) throws IOException {
        List<String> hrefs = extractHrefs(bodyXml);
        StringBuilder xml  = startMultistatus();

        for (String href : hrefs) {
            // strip context path and /caldav/ prefix, then split
            String relative = href.replaceAll(".*?/caldav/", "").replaceAll("^/+|/+$", "");
            String[] parts  = relative.split("/", 3);
            if (parts.length < 3) continue;

            String owner    = parts[0];
            String calendar = parts[1];
            String filename = parts[2];

            if (!storage.eventExists(owner, calendar, filename)) {
                xml.append("<D:response>\n")
                   .append("  <D:href>").append(escapeXml(href)).append("</D:href>\n")
                   .append("  <D:status>HTTP/1.1 404 Not Found</D:status>\n")
                   .append("</D:response>\n");
                continue;
            }
            byte[] content = storage.loadEvent(owner, calendar, filename);
            String ics     = new String(content, StandardCharsets.UTF_8);
            xml.append(eventDataResponse(href, storage.etag(owner, calendar, filename), ics));
        }
        return multistatusResponse(xml);
    }

    // ── MKCOL ─────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleMkcol(String username, HttpServletRequest request)
            throws IOException {
        PathInfo p = parsePath(request);
        if (p.calendar == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (storage.calendarExists(username, p.calendar))
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        storage.createCalendar(username, p.calendar);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ── PUT ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handlePut(String username, HttpServletRequest request)
            throws IOException {
        PathInfo p = parsePath(request);
        if (p.calendar == null || p.filename == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

        boolean exists = storage.eventExists(username, p.calendar, p.filename);

        String ifMatch     = request.getHeader("If-Match");
        String ifNoneMatch = request.getHeader("If-None-Match");

        if (ifMatch != null && !ifMatch.equals("*")) {
            if (!exists) return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
            String current = "\"" + storage.etag(username, p.calendar, p.filename) + "\"";
            if (!ifMatch.equals(current))
                return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }
        if ("*".equals(ifNoneMatch) && exists)
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();

        byte[] body = request.getInputStream().readAllBytes();
        storage.saveEvent(username, p.calendar, p.filename, body);
        String etag = storage.etag(username, p.calendar, p.filename);

        HttpHeaders h = new HttpHeaders();
        h.setETag("\"" + etag + "\"");
        return ResponseEntity.status(exists ? HttpStatus.NO_CONTENT : HttpStatus.CREATED)
                .headers(h).build();
    }

    // ── GET ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleGet(String username, HttpServletRequest request)
            throws IOException {
        PathInfo p = parsePath(request);
        if (p.filename == null)
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        if (!storage.eventExists(username, p.calendar, p.filename))
            return ResponseEntity.notFound().build();
        byte[] content = storage.loadEvent(username, p.calendar, p.filename);
        HttpHeaders h  = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/calendar; charset=utf-8"));
        h.setETag("\"" + storage.etag(username, p.calendar, p.filename) + "\"");
        return ResponseEntity.ok().headers(h).body(content);
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> handleDelete(String username, HttpServletRequest request)
            throws IOException {
        PathInfo p = parsePath(request);
        if (p.filename == null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        storage.deleteEvent(username, p.calendar, p.filename);
        return ResponseEntity.noContent().build();
    }

    // ── XML builders ──────────────────────────────────────────────────────

    private StringBuilder startMultistatus() {
        return new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<D:multistatus xmlns:D=\"DAV:\"")
                .append(" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"")
                .append(" xmlns:CS=\"http://calendarserver.org/ns/\">\n");
    }

    private ResponseEntity<byte[]> multistatusResponse(StringBuilder xml) {
        xml.append("</D:multistatus>");
        HttpHeaders h = new HttpHeaders();
        h.add("DAV", DAV_CAPS);
        h.setContentType(MediaType.APPLICATION_XML);
        return ResponseEntity.status(207).headers(h)
                .body(xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String principalResponse(String href, String username) {
        return "<D:response>\n"
                + "  <D:href>" + href + "</D:href>\n"
                + "  <D:propstat>\n  <D:prop>\n"
                + "    <D:resourcetype><D:collection/><D:principal/></D:resourcetype>\n"
                + "    <D:displayname>" + escapeXml(username) + "</D:displayname>\n"
                + "    <C:calendar-home-set><D:href>" + href + "</D:href></C:calendar-home-set>\n"
                + "    <C:calendar-user-address-set>"
                + "<D:href>mailto:" + escapeXml(username) + "@jadicale.local</D:href>"
                + "</C:calendar-user-address-set>\n"
                + "    <D:current-user-principal><D:href>" + href + "</D:href></D:current-user-principal>\n"
                + "  </D:prop>\n  <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n</D:response>\n";
    }

    private String calendarResponse(String href, String name) {
        return "<D:response>\n"
                + "  <D:href>" + href + "</D:href>\n"
                + "  <D:propstat>\n  <D:prop>\n"
                + "    <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>\n"
                + "    <D:displayname>" + escapeXml(name) + "</D:displayname>\n"
                + "    <CS:getctag>\"" + System.currentTimeMillis() + "\"</CS:getctag>\n"
                + "    <D:sync-token>0</D:sync-token>\n"
                + "  </D:prop>\n  <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n</D:response>\n";
    }

    private String eventMetaResponse(String href, String etag) {
        return "<D:response>\n"
                + "  <D:href>" + href + "</D:href>\n"
                + "  <D:propstat>\n  <D:prop>\n"
                + "    <D:resourcetype/>\n"
                + "    <D:getcontenttype>text/calendar; charset=utf-8</D:getcontenttype>\n"
                + "    <D:getetag>\"" + etag + "\"</D:getetag>\n"
                + "  </D:prop>\n  <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n</D:response>\n";
    }

    private String eventDataResponse(String href, String etag, String ics) {
        return "<D:response>\n"
                + "  <D:href>" + escapeXml(href) + "</D:href>\n"
                + "  <D:propstat>\n  <D:prop>\n"
                + "    <D:getetag>\"" + etag + "\"</D:getetag>\n"
                + "    <C:calendar-data>" + escapeXml(ics) + "</C:calendar-data>\n"
                + "  </D:prop>\n  <D:status>HTTP/1.1 200 OK</D:status>\n"
                + "  </D:propstat>\n</D:response>\n";
    }

    // ── Path / URL helpers ────────────────────────────────────────────────

    private PathInfo parsePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (!ctx.isEmpty() && uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        String stripped = uri.replaceFirst("^/caldav/?", "").replaceAll("/$", "");
        String[] parts  = stripped.isEmpty() ? new String[0] : stripped.split("/", 3);
        PathInfo p = new PathInfo();
        if (parts.length >= 2) p.calendar = parts[1];
        if (parts.length >= 3) p.filename = parts[2];
        return p;
    }

    private String base(HttpServletRequest request) {
        return request.getContextPath() + "/caldav/";
    }

    private static class PathInfo {
        String calendar;
        String filename;
    }

    // ── iCalendar time-range filter ───────────────────────────────────────

    private boolean eventInRange(String ics, String rangeStart, String rangeEnd) {
        Matcher mStart = Pattern.compile("DTSTART[^:]*:([0-9T]+)").matcher(ics);
        if (!mStart.find()) return true;
        String dtStart = normalize(mStart.group(1));
        String rs      = normalize(rangeStart);
        String re      = normalize(rangeEnd);

        Matcher mEnd = Pattern.compile("DTEND[^:]*:([0-9T]+)").matcher(ics);
        if (mEnd.find()) {
            String dtEnd = normalize(mEnd.group(1));
            return dtStart.compareTo(re) < 0 && dtEnd.compareTo(rs) > 0;
        }
        return dtStart.compareTo(rs) >= 0 && dtStart.compareTo(re) < 0;
    }

    private String normalize(String dt) {
        return dt.replaceAll("[^0-9T]", "");
    }

    // ── XML string helpers ────────────────────────────────────────────────

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String extractAttr(String xml, String element, String attr) {
        Matcher m = Pattern.compile(
                "<[^:>]*:" + element + "[^>]*\\s" + attr + "=\"([^\"]+)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private List<String> extractHrefs(String xml) {
        List<String> hrefs = new ArrayList<>();
        Matcher m = Pattern.compile(
                "<[^:>]*:href[^>]*>\\s*([^<]+?)\\s*</[^:>]*:href>").matcher(xml);
        while (m.find()) hrefs.add(m.group(1).trim());
        return hrefs;
    }
}
