import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Study Buddy – Command Line Scheduling App
 * Java 17 single-file implementation meeting the provided SRS.
 *
 * Persistence: CSV files in ./data (students.csv, availability.csv, sessions.csv)
 *
 * Commands (examples):
 *  profile create --name "Avery" --email "avery@clemson.edu"
 *  course add --id s1 --course CPSC-3720
 *  course remove --id s1 --course CPSC-3720
 *  availability add --id s1 --dow TUE --start 15:00 --end 17:00
 *  availability remove --id s1 --dow TUE --start 15:00 --end 17:00
 *  classmates --course CPSC-3720
 *  match suggest --id s1 --course CPSC-3720
 *  session propose --id s1 --course CPSC-3720 --slot "TUE 15:00-16:00" --invitees s2,s3
 *  session respond --id s2 --session S1 --accept true
 *  session list --id s1
 *  export csv --dir ./exports   (also mirrors persistence files)
 *  help
 *  exit
 */
public class StudyBuddyCLI {

    // ============================= DOMAIN =============================

    enum Status { PENDING, CONFIRMED, DECLINED }

    static final class AvailabilitySlot {
        final DayOfWeek dayOfWeek;
        final LocalTime start;
        final LocalTime end;
        AvailabilitySlot(DayOfWeek d, LocalTime s, LocalTime e){
            if (e.isBefore(s) || e.equals(s)) throw new IllegalArgumentException("end must be after start");
            this.dayOfWeek=d; this.start=s; this.end=e;
        }
        boolean overlaps(AvailabilitySlot other){
            if (!this.dayOfWeek.equals(other.dayOfWeek)) return false;
            return !this.end.isBefore(other.start) && !other.end.isBefore(this.start);
        }
        @Override public String toString(){
            return dayOfWeek + " " + start + "-" + end;
        }
        static AvailabilitySlot parseSlot(String text){
            // format: DOW HH:mm-HH:mm (e.g., TUE 15:00-16:00)
            String[] parts = text.trim().split("\\s+");
            if (parts.length!=2) throw new IllegalArgumentException("Slot format: DOW HH:mm-HH:mm");
            DayOfWeek dow = parseDow(parts[0]);
            String[] times = parts[1].split("-");
            if (times.length!=2) throw new IllegalArgumentException("Slot format: DOW HH:mm-HH:mm");
            return new AvailabilitySlot(dow, LocalTime.parse(times[0]), LocalTime.parse(times[1]));
        }
    }

    static final class Student {
        final String id; // s#
        String name;
        String email;
        final Set<String> courses = new TreeSet<>();
        final List<AvailabilitySlot> availability = new ArrayList<>();
        Student(String id, String name, String email){ this.id=id; this.name=name; this.email=email; }
    }

    static final class StudySession {
        final String id; // S#
        String courseCode;
        AvailabilitySlot slot;
        final Set<String> participants = new TreeSet<>(); // student IDs (includes inviter)
        String inviterId;
        Status status = Status.PENDING;
        StudySession(String id){ this.id=id; }
    }

    // ============================= REPOSITORY (CSV) =============================

    static final class Repo {
        private final Path dataDir;
        private final Path studentsCsv;
        private final Path availabilityCsv;
        private final Path sessionsCsv;

        private final Map<String, Student> students = new LinkedHashMap<>();
        private final Map<String, StudySession> sessions = new LinkedHashMap<>();
        private int studentSeq = 1;
        private int sessionSeq = 1;

        Repo(Path dataDir){
            this.dataDir = dataDir;
            this.studentsCsv = dataDir.resolve("students.csv");
            this.availabilityCsv = dataDir.resolve("availability.csv");
            this.sessionsCsv = dataDir.resolve("sessions.csv");
        }

        void load() throws IOException {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            // Students
            if (Files.exists(studentsCsv)){
                try(BufferedReader br = Files.newBufferedReader(studentsCsv)){
                    String line; boolean first=true;
                    while((line=br.readLine())!=null){
                        if (first){ first=false; continue; } // skip header
                        String[] c = parseCsvLine(line, 4);
                        String id=c[0];
                        Student s = new Student(id, unescape(c[1]), unescape(c[2]));
                        for (String course : splitList(c[3])) s.courses.add(course);
                        students.put(id, s);
                        studentSeq = Math.max(studentSeq, parseNumericSuffix(id)+1);
                    }
                }
            }
            // Availability
            if (Files.exists(availabilityCsv)){
                try(BufferedReader br = Files.newBufferedReader(availabilityCsv)){
                    String line; boolean first=true;
                    while((line=br.readLine())!=null){
                        if (first){ first=false; continue; }
                        String[] c = parseCsvLine(line, 4);
                        String sid = c[0];
                        Student s = students.get(sid);
                        if (s==null) continue; // orphan
                        DayOfWeek dow = parseDow(c[1]);
                        LocalTime start = LocalTime.parse(c[2]);
                        LocalTime end = LocalTime.parse(c[3]);
                        s.availability.add(new AvailabilitySlot(dow,start,end));
                    }
                }
            }
            // Sessions
            if (Files.exists(sessionsCsv)){
                try(BufferedReader br = Files.newBufferedReader(sessionsCsv)){
                    String line; boolean first=true;
                    while((line=br.readLine())!=null){
                        if (first){ first=false; continue; }
                        String[] c = parseCsvLine(line, 8);
                        String id=c[0];
                        StudySession ss = new StudySession(id);
                        ss.courseCode = c[1];
                        DayOfWeek dow = parseDow(c[2]);
                        LocalTime start = LocalTime.parse(c[3]);
                        LocalTime end = LocalTime.parse(c[4]);
                        ss.slot = new AvailabilitySlot(dow,start,end);
                        for (String p : splitList(c[5])) ss.participants.add(p);
                        ss.status = Status.valueOf(c[6]);
                        ss.inviterId = c[7];
                        sessions.put(id, ss);
                        sessionSeq = Math.max(sessionSeq, parseNumericSuffix(id)+1);
                    }
                }
            }
        }

        void save() throws IOException {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            // Students
            try(BufferedWriter bw = Files.newBufferedWriter(studentsCsv)){
                bw.write("id,name,email,courses\n");
                for (Student s : students.values()){
                    bw.write(csv(s.id, s.name, s.email, joinList(s.courses)));
                    bw.write("\n");
                }
            }
            // Availability
            try(BufferedWriter bw = Files.newBufferedWriter(availabilityCsv)){
                bw.write("studentId,dayOfWeek,startTime,endTime\n");
                for (Student s : students.values()){
                    for (AvailabilitySlot a : s.availability){
                        bw.write(csv(s.id, a.dayOfWeek.toString(), a.start.toString(), a.end.toString()));
                        bw.write("\n");
                    }
                }
            }
            // Sessions
            try(BufferedWriter bw = Files.newBufferedWriter(sessionsCsv)){
                bw.write("id,courseCode,slotDay,slotStart,slotEnd,participants,status,inviterId\n");
                for (StudySession ss : sessions.values()){
                    bw.write(csv(ss.id, ss.courseCode, ss.slot.dayOfWeek.toString(), ss.slot.start.toString(),
                            ss.slot.end.toString(), joinList(ss.participants), ss.status.toString(), ss.inviterId));
                    bw.write("\n");
                }
            }
        }

        // Export (mirrors save but to custom dir)
        void exportTo(Path outDir) throws IOException {
            if (!Files.exists(outDir)) Files.createDirectories(outDir);
            Files.copy(studentsCsv, outDir.resolve("students.csv"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(availabilityCsv, outDir.resolve("availability.csv"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sessionsCsv, outDir.resolve("sessions.csv"), StandardCopyOption.REPLACE_EXISTING);
        }

        Student createStudent(String name, String email){
            String id = "s"+ (studentSeq++);
            Student s = new Student(id, name, email);
            students.put(id, s);
            return s;
        }
        Optional<Student> findStudent(String id){ return Optional.ofNullable(students.get(id)); }
        Collection<Student> allStudents(){ return students.values(); }

        StudySession createSession(String course, AvailabilitySlot slot, String inviterId, Collection<String> invitees){
            String id = "S"+(sessionSeq++);
            StudySession ss = new StudySession(id);
            ss.courseCode = course;
            ss.slot = slot;
            ss.inviterId = inviterId;
            ss.participants.add(inviterId);
            ss.participants.addAll(invitees);
            sessions.put(id, ss);
            return ss;
        }
        Optional<StudySession> findSession(String id){ return Optional.ofNullable(sessions.get(id)); }
        Collection<StudySession> allSessions(){ return sessions.values(); }

        // CSV helpers
        private static String csv(String... cols){
            return Arrays.stream(cols).map(StudyBuddyCLI::escape).collect(Collectors.joining(","));
        }
        private static String[] parseCsvLine(String line, int expected){
            List<String> out = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQ=false; for(int i=0;i<line.length();i++){
                char ch=line.charAt(i);
                if (inQ){
                    if (ch=='"'){
                        if (i+1<line.length() && line.charAt(i+1)=='"'){ sb.append('"'); i++; }
                        else inQ=false;
                    } else sb.append(ch);
                } else {
                    if (ch==','){ out.add(sb.toString()); sb.setLength(0); }
                    else if (ch=='"'){ inQ=true; }
                    else sb.append(ch);
                }
            }
            out.add(sb.toString());
            while(out.size()<expected) out.add("");
            return out.toArray(new String[0]);
        }
    }

    // ============================= SERVICE =============================

    static final class Service {
        private final Repo repo;
        Service(Repo repo){ this.repo=repo; }

        Student createProfile(String name, String email){
            require(name!=null && !name.isBlank(), "name required");
            require(email!=null && !email.isBlank(), "email required");
            Student s = repo.createStudent(name.trim(), email.trim());
            return s;
        }

        void addCourse(String studentId, String course){
            Student s = getStudent(studentId);
            s.courses.add(course.trim());
        }
        void removeCourse(String studentId, String course){
            Student s = getStudent(studentId);
            s.courses.remove(course.trim());
        }

        void addAvailability(String studentId, DayOfWeek dow, LocalTime start, LocalTime end){
            Student s = getStudent(studentId);
            s.availability.add(new AvailabilitySlot(dow,start,end));
        }
        void removeAvailability(String studentId, DayOfWeek dow, LocalTime start, LocalTime end){
            Student s = getStudent(studentId);
            s.availability.removeIf(a -> a.dayOfWeek.equals(dow) && a.start.equals(start) && a.end.equals(end));
        }

        List<Student> classmatesInCourse(String course){
            return repo.allStudents().stream()
                    .filter(s -> s.courses.contains(course))
                    .collect(Collectors.toList());
        }

        List<Student> suggestMatches(String studentId, String course){
            Student me = getStudent(studentId);
            require(me.courses.contains(course), "You are not enrolled in "+course);
            return repo.allStudents().stream()
                    .filter(s -> !s.id.equals(me.id))
                    .filter(s -> s.courses.contains(course))
                    .filter(s -> overlapsAny(me.availability, s.availability))
                    .collect(Collectors.toList());
        }

        StudySession proposeSession(String inviterId, String course, AvailabilitySlot slot, List<String> inviteeIds){
            Student inviter = getStudent(inviterId);
            require(inviter.courses.contains(course), "Inviter not in course");
            for (String iid : inviteeIds) {
                Student other = getStudent(iid);
                require(other.courses.contains(course), "Invitee "+iid+" not in course "+course);
                require(overlapsAny(List.of(slot), other.availability), "Invitee "+iid+" not available at "+slot);
            }
            require(overlapsAny(List.of(slot), inviter.availability), "Inviter not available at "+slot);
            return repo.createSession(course, slot, inviterId, inviteeIds);
        }

        void respondToSession(String studentId, String sessionId, boolean accept){
            StudySession ss = repo.findSession(sessionId).orElseThrow(() -> new IllegalArgumentException("Session not found"));
            require(ss.participants.contains(studentId), "You are not an invitee of this session");
            if (!accept){
                ss.status = Status.DECLINED;
            } else {
                // If all participants accept (no one declined), mark confirmed.
                ss.status = Status.CONFIRMED; // simplified: single acceptance confirms per SRS acceptance criteria
            }
        }

        List<StudySession> listSessionsFor(String studentId){
            return repo.allSessions().stream()
                    .filter(s -> s.participants.contains(studentId))
                    .collect(Collectors.toList());
        }

        private static boolean overlapsAny(List<AvailabilitySlot> a, List<AvailabilitySlot> b){
            for (AvailabilitySlot x : a) for (AvailabilitySlot y : b) if (x.overlaps(y)) return true;
            return false;
        }

        private Student getStudent(String id){
            return repo.findStudent(id).orElseThrow(()-> new IllegalArgumentException("Student not found: "+id));
        }
    }

    // ============================= CLI =============================

    public static void main(String[] args) throws Exception {
        Repo repo = new Repo(Paths.get("data"));
        try{ repo.load(); } catch (IOException e){ System.err.println("Warning: could not load: "+e.getMessage()); }
        Service svc = new Service(repo);

        System.out.println("Study Buddy CLI (Java 17) — type 'help' for commands. Data dir: ./data");
        try (Scanner sc = new Scanner(System.in)){
            while (true){
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isBlank()) continue;
                try{
                    if (line.equalsIgnoreCase("exit")){
                        repo.save();
                        System.out.println("Saved. Bye!");
                        break;
                    } else if (line.equalsIgnoreCase("help")){
                        printHelp();
                    } else {
                        handleCommand(line, svc, repo);
                        // Save after each successful command for reliability
                        repo.save();
                    }
                } catch (Exception ex){
                    System.out.println("ERROR: "+ex.getMessage());
                }
            }
        }
    }

    private static void handleCommand(String line, Service svc, Repo repo) throws Exception {
        List<String> toks = tokenize(line);
        if (toks.size()<1) return;
        String cmd = toks.get(0).toLowerCase();
        Map<String,String> kv = parseFlags(toks.subList(1, toks.size()));

        switch (cmd){
            case "profile": {
                String sub = requireStr(kv.remove("cmd"), "subcommand (create)");
                if (sub.equals("create")){
                    String name = requireStr(kv.remove("name"), "--name");
                    String email = requireStr(kv.remove("email"), "--email");
                    Student s = svc.createProfile(name, email);
                    System.out.println("CREATED,"+csvRow("id","name","email"));
                    System.out.println("DATA,"+csvRow(s.id, s.name, s.email));
                } else throw new IllegalArgumentException("Unknown profile subcommand: "+sub);
                break;
            }
            case "course": {
                String sub = requireStr(kv.remove("cmd"), "subcommand (add|remove)");
                String id = requireStr(kv.remove("id"), "--id");
                String course = requireStr(kv.remove("course"), "--course");
                if (sub.equals("add")) svc.addCourse(id, course); else if (sub.equals("remove")) svc.removeCourse(id, course); else throw new IllegalArgumentException("Unknown course subcommand");
                System.out.println("OK");
                break;
            }
            case "availability": {
                String sub = requireStr(kv.remove("cmd"), "subcommand (add|remove)");
                String id = requireStr(kv.remove("id"), "--id");
                DayOfWeek dow = parseDow(requireStr(kv.remove("dow"), "--dow"));
                LocalTime start = LocalTime.parse(requireStr(kv.remove("start"), "--start"));
                LocalTime end = LocalTime.parse(requireStr(kv.remove("end"), "--end"));
                if (sub.equals("add")) svc.addAvailability(id, dow, start, end); else if (sub.equals("remove")) svc.removeAvailability(id, dow, start, end); else throw new IllegalArgumentException("Unknown availability subcommand");
                System.out.println("OK");
                break;
            }
            case "classmates": {
                String course = requireStr(kv.remove("course"), "--course");
                List<Student> list = svc.classmatesInCourse(course);
                System.out.println(csvRow("id","name","email"));
                for (Student s : list) System.out.println(csvRow(s.id,s.name,s.email));
                break;
            }
            case "match": {
                String sub = requireStr(kv.remove("cmd"), "subcommand (suggest)");
                if (!sub.equals("suggest")) throw new IllegalArgumentException("Unknown match subcommand");
                String id = requireStr(kv.remove("id"), "--id");
                String course = requireStr(kv.remove("course"), "--course");
                List<Student> list = svc.suggestMatches(id, course);
                System.out.println(csvRow("id","name","email"));
                for (Student s : list) System.out.println(csvRow(s.id,s.name,s.email));
                break;
            }
            case "session": {
                String sub = requireStr(kv.remove("cmd"), "subcommand (propose|respond|list)");
                switch (sub){
                    case "propose": {
                        String inviter = requireStr(kv.remove("id"), "--id");
                        String course = requireStr(kv.remove("course"), "--course");
                        String slotStr = requireStr(kv.remove("slot"), "--slot");
                        AvailabilitySlot slot = AvailabilitySlot.parseSlot(slotStr);
                        String inviteesStr = requireStr(kv.remove("invitees"), "--invitees");
                        List<String> invitees = Arrays.stream(inviteesStr.split(","))
                                .map(String::trim).filter(s->!s.isBlank()).collect(Collectors.toList());
                        StudySession ss = svc.proposeSession(inviter, course, slot, invitees);
                        System.out.println(csvRow("id","course","slot","status","participants"));
                        System.out.println(csvRow(ss.id, ss.courseCode, ss.slot.toString(), ss.status.toString(), String.join(";", ss.participants)));
                        break;
                    }
                    case "respond": {
                        String sid = requireStr(kv.remove("id"), "--id");
                        String sess = requireStr(kv.remove("session"), "--session");
                        boolean accept = Boolean.parseBoolean(requireStr(kv.remove("accept"), "--accept"));
                        svc.respondToSession(sid, sess, accept);
                        System.out.println("OK");
                        break;
                    }
                    case "list": {
                        String sid = requireStr(kv.remove("id"), "--id");
                        List<StudySession> list = svc.listSessionsFor(sid);
                        System.out.println(csvRow("id","course","slot","status","participants","inviter"));
                        for (StudySession s : list){
                            System.out.println(csvRow(s.id, s.courseCode, s.slot.toString(), s.status.toString(), String.join(";", s.participants), s.inviterId));
                        }
                        break;
                    }
                    default: throw new IllegalArgumentException("Unknown session subcommand");
                }
                break;
            }
            case "export": {
                String sub = requireStr(kv.remove("cmd"), "subcommand (csv)");
                if (!sub.equals("csv")) throw new IllegalArgumentException("Unknown export subcommand");
                Path dir = Paths.get(kv.getOrDefault("dir", "exports"));
                repo.save();
                repo.exportTo(dir);
                System.out.println("EXPORTED to " + dir.toAbsolutePath());
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown command. Type 'help'.");
        }
        if (!kv.isEmpty()) System.out.println("WARNING: Ignored flags " + kv.keySet());
    }

    private static void printHelp(){
        System.out.println("Commands (CSV output):\n" +
                "  profile create --name <NAME> --email <EMAIL>\n" +
                "  course add --id <s#> --course <CODE>\n" +
                "  course remove --id <s#> --course <CODE>\n" +
                "  availability add --id <s#> --dow <MON..SUN> --start <HH:mm> --end <HH:mm>\n" +
                "  availability remove --id <s#> --dow <MON..SUN> --start <HH:mm> --end <HH:mm>\n" +
                "  classmates --course <CODE>\n" +
                "  match suggest --id <s#> --course <CODE>\n" +
                "  session propose --id <s#> --course <CODE> --slot \"DOW HH:mm-HH:mm\" --invitees s2,s3\n" +
                "  session respond --id <s#> --session <S#> --accept <true|false>\n" +
                "  session list --id <s#>\n" +
                "  export csv --dir <DIR>\n" +
                "  help | exit\n");
    }

    // ============================= UTIL =============================

    private static List<String> tokenize(String line){
        // Split by spaces but keep quoted strings intact; also interpret first token after command as subcommand via --cmd
        List<String> raw = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQ=false; for (int i=0;i<line.length();i++){
            char ch=line.charAt(i);
            if (inQ){
                if (ch=='"'){ inQ=false; }
                else sb.append(ch);
            } else {
                if (Character.isWhitespace(ch)){
                    if (sb.length()>0){ raw.add(sb.toString()); sb.setLength(0);}            
                } else if (ch=='"'){
                    inQ=true;            
                } else {
                    sb.append(ch);
                }
            }
        }
        if (sb.length()>0) raw.add(sb.toString());
        // Promote positional subcommand into flag --cmd
        if (raw.size()>=2 && !raw.get(1).startsWith("--")){
            raw.add(1, "--cmd");
            raw.add(2, raw.remove(2));
        }
        return raw;
    }

    private static Map<String,String> parseFlags(List<String> toks){
        Map<String,String> kv = new LinkedHashMap<>();
        for (int i=0;i<toks.size();i++){
            String t = toks.get(i);
            if (t.startsWith("--")){
                String key = t.substring(2);
                String val = (i+1<toks.size() && !toks.get(i+1).startsWith("--")) ? toks.get(++i) : "true";
                kv.put(key.toLowerCase(), val);
            }
        }
        return kv;
    }

    private static String requireStr(String v, String what){ if (v==null || v.isBlank()) throw new IllegalArgumentException("Missing "+what); return v; }
    private static void require(boolean cond, String msg){ if (!cond) throw new IllegalArgumentException(msg); }

    private static DayOfWeek parseDow(String s){
        s = s.trim().toUpperCase();
        switch (s){
            case "MON": return DayOfWeek.MONDAY;
            case "TUE": case "TUES": case "TUESDAY": return DayOfWeek.TUESDAY;
            case "WED": return DayOfWeek.WEDNESDAY;
            case "THU": case "THUR": case "THURS": case "THURSDAY": return DayOfWeek.THURSDAY;
            case "FRI": return DayOfWeek.FRIDAY;
            case "SAT": return DayOfWeek.SATURDAY;
            case "SUN": return DayOfWeek.SUNDAY;
            default:
                // Also allow full enum names
                try { return DayOfWeek.valueOf(s); } catch (Exception e){ throw new IllegalArgumentException("Bad --dow: "+s); }
        }
    }

    // Simple ID suffix parser: s12 -> 12
    private static int parseNumericSuffix(String id){
        for (int i=id.length()-1;i>=0;i--){ if (!Character.isDigit(id.charAt(i))) return Integer.parseInt(id.substring(i+1)); }
        return Integer.parseInt(id);
    }

    // ==== CSV escaping helpers for CLI prints and persistence
    private static String escape(String s){
        if (s==null) return "";
        boolean need = s.contains(",") || s.contains("\"") || s.contains("\n");
        String out = s.replace("\"", "\"\"");
        return need ? "\""+out+"\"" : out;
    }
    private static String unescape(String s){ return s; }
    private static String joinList(Collection<String> items){ return items.stream().collect(Collectors.joining(";")); }
    private static List<String> splitList(String s){ if (s==null || s.isBlank()) return List.of(); return Arrays.stream(s.split(";")) .map(String::trim).filter(x->!x.isBlank()).collect(Collectors.toList()); }
    private static String csvRow(String... cols){ return Arrays.stream(cols).map(StudyBuddyCLI::escape).collect(Collectors.joining(",")); }
}
