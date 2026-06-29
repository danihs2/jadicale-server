# Jadicale

A Spring Boot CalDAV server — a clean replacement for Radicale with calendar sharing.

## Build

```bash
mvn clean package
```

Produces `target/jadicale.war` — deployable on any standard Tomcat installation (ROOT or context path).

## Setup

### 1. Create users

```bash
htpasswd -BC 10 ~/.jadicale/passwd alice
htpasswd -B ~/.jadicale/passwd bob
```

### 2. Configure (optional)

`application.properties` defaults:

```
jadicale.passwd-file=${user.home}/.jadicale/passwd
jadicale.storage-dir=${user.home}/.jadicale/collections
```

### 3. Run standalone

```bash
java -jar target/jadicale.war
```

Or deploy `jadicale.war` to Tomcat as ROOT or under `/jadicale`.

---

## Verify with curl

### OPTIONS — server capabilities (no auth required)

```bash
curl -v -X OPTIONS http://localhost:8080/caldav/
# Expect: 200, DAV: 1, 2, 3, calendar-access
```

### Create a calendar

```bash
curl -v -X MKCOL http://localhost:8080/caldav/alice/personal/ -u alice:password
# Expect: 201 Created
```

### Create an event

```bash
curl -v -X PUT http://localhost:8080/caldav/alice/personal/standup.ics \
  -u alice:password \
  -H "Content-Type: text/calendar" \
  --data-binary "BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Jadicale//EN
BEGIN:VEVENT
UID:standup-001@jadicale
SUMMARY:Team standup
DTSTART:20260701T090000Z
DTEND:20260701T093000Z
END:VEVENT
END:VCALENDAR"
# Expect: 201 Created with ETag header
```

### List calendar contents

```bash
curl -v -X PROPFIND http://localhost:8080/caldav/alice/personal/ \
  -u alice:password \
  -H "Depth: 1" \
  -H "Content-Type: application/xml" \
  -d '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getetag/></D:prop></D:propfind>'
# Expect: 207 Multi-Status with calendar + event listed
```

### Retrieve an event

```bash
curl -v http://localhost:8080/caldav/alice/personal/standup.ics -u alice:password
# Expect: 200 with text/calendar content
```

### Delete an event

```bash
curl -v -X DELETE http://localhost:8080/caldav/alice/personal/standup.ics -u alice:password
# Expect: 204 No Content
```

### Principal discovery (used by CalDAV clients on first connect)

```bash
curl -v -X PROPFIND http://localhost:8080/caldav/alice/ \
  -u alice:password \
  -H "Depth: 0" \
  -H "Content-Type: application/xml" \
  -d '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:current-user-principal/><C:calendar-home-set xmlns:C="urn:ietf:params:xml:ns:caldav"/></D:prop></D:propfind>'
# Expect: 207 with calendar-home-set pointing to /caldav/alice/
```

---

## Storage layout

```
~/.jadicale/
  passwd                      # htpasswd-format credentials
  collections/
    alice/
      personal/               # calendar directory
        standup.ics           # individual event files
      team/
        metadata.json         # sharing rules (roadmap)
    bob/
      ...
```

---

## Roadmap to full acceptance tests

- [ ] REPORT calendar-query (time-range filter)
- [ ] REPORT calendar-multiget
- [ ] Recurring event support
- [ ] Calendar sharing metadata + dynamic PROPFIND across users
- [ ] Thymeleaf web UI (list calendars, manage sharing rules, set color)
- [ ] AgendaV + Thunderbird interop testing
- [ ] Upgrade to Spring Boot 4.10+ / JDK 26

---

## License

Public Domain — no restrictions.
