package com.bookloop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequestMapping("/api")
public class Api {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
  private static final Map<String, Object> OK = Map.of("ok", true);
  Api(JdbcTemplate db) { this.db = db; }

  // ---------- helpers ----------
  static ResponseStatusException err(int c, String m) { return new ResponseStatusException(HttpStatus.valueOf(c), m); }
  static boolean has(Map<String, String> p, String k) { return p.get(k) != null && !p.get(k).isBlank(); }
  static int num(Object o) { return ((Number) o).intValue(); }
  Map<String, Object> user(String t) {
    if (t == null || t.isBlank()) throw err(401, "Please log in");
    var l = db.queryForList("SELECT id,name,email,status,role FROM students WHERE token=?", t);
    if (l.isEmpty()) throw err(401, "Session expired, please log in again");
    return l.get(0);
  }
  Map<String, Object> verified(String t) {
    var u = user(t);
    if (!"VERIFIED".equals(u.get("status"))) throw err(403, "Your college ID is not verified yet");
    return u;
  }
  Map<String, Object> admin(String t) {
    var u = user(t);
    if (!"ADMIN".equals(u.get("role"))) throw err(403, "Admins only");
    return u;
  }
  void notify(int to, String msg) { db.update("INSERT INTO notifications(recipient_id,message) VALUES(?,?)", to, msg); }

  // ---------- Module 1: registration & login ----------
  @PostMapping("/register") public Map<String, Object> register(@RequestBody Map<String, String> b) {
    if (!db.queryForList("SELECT id FROM students WHERE email=?", b.get("email")).isEmpty()) throw err(409, "Email already registered");
    if (b.get("idCard") == null) throw err(400, "Please upload your college ID card");
    db.update("INSERT INTO students(name,email,phone,department,semester,password_hash,id_card) VALUES(?,?,?,?,?,?,?)",
        b.get("name"), b.get("email"), b.get("phone"), b.get("department"), Integer.parseInt(b.get("semester")), enc.encode(b.get("password")), b.get("idCard"));
    return OK;
  }
  @PostMapping("/login") public Map<String, Object> login(@RequestBody Map<String, String> b) {
    var l = db.queryForList("SELECT id,name,status,role,password_hash FROM students WHERE email=?", b.get("email"));
    if (l.isEmpty() || !enc.matches(b.get("password"), (String) l.get(0).get("password_hash"))) throw err(401, "Wrong email or password");
    var u = l.get(0);
    String t = UUID.randomUUID().toString();
    db.update("UPDATE students SET token=? WHERE id=?", t, u.get("id"));
    return Map.of("token", t, "name", u.get("name"), "status", u.get("status"), "role", u.get("role"));
  }
  @GetMapping("/me") public Map<String, Object> me(@RequestHeader(value = "X-Token", required = false) String t) { return user(t); }

  // ---------- Module 2 & 3: books, search, filters ----------
  @GetMapping("/books") public List<Map<String, Object>> books(@RequestHeader(value = "X-Token", required = false) String t, @RequestParam Map<String, String> p) {
    user(t);
    StringBuilder q = new StringBuilder("SELECT b.id,b.title,b.author,b.subject,b.semester,b.department,b.book_condition,b.price,b.type,b.cover,b.owner_id,s.name owner FROM books b JOIN students s ON s.id=b.owner_id WHERE b.availability='AVAILABLE'");
    List<Object> a = new ArrayList<>();
    if (has(p, "q")) { q.append(" AND (b.title LIKE ? OR b.author LIKE ?)"); a.add("%" + p.get("q") + "%"); a.add("%" + p.get("q") + "%"); }
    for (String f : List.of("subject", "semester", "department", "type")) if (has(p, f)) { q.append(" AND b.").append(f).append("=?"); a.add(p.get(f)); }
    if (has(p, "maxPrice")) { q.append(" AND b.price<=?"); a.add(Double.parseDouble(p.get("maxPrice"))); }
    return db.queryForList(q + " ORDER BY b.id DESC", a.toArray());
  }
  @GetMapping("/books/mine") public List<Map<String, Object>> mine(@RequestHeader("X-Token") String t) {
    return db.queryForList("SELECT id,title,type,price,availability FROM books WHERE owner_id=? AND availability<>'REMOVED' ORDER BY id DESC", num(user(t).get("id")));
  }
  @PostMapping("/books") public Map<String, Object> addBook(@RequestHeader("X-Token") String t, @RequestBody Map<String, Object> b) {
    var u = verified(t);
    String type = String.valueOf(b.get("type"));
    double price = "SELL".equals(type) ? Double.parseDouble(String.valueOf(b.get("price"))) : 0;
    db.update("INSERT INTO books(title,author,subject,semester,department,book_condition,price,type,cover,owner_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
        b.get("title"), b.get("author"), b.get("subject"), Integer.parseInt(String.valueOf(b.get("semester"))), b.get("department"),
        b.get("book_condition"), price, type, b.get("cover"), u.get("id"));
    return OK;
  }
  @DeleteMapping("/books/{id}") public Map<String, Object> removeBook(@RequestHeader("X-Token") String t, @PathVariable int id) {
    db.update("UPDATE books SET availability='REMOVED' WHERE id=? AND owner_id=? AND availability='AVAILABLE'", id, user(t).get("id"));
    return OK;
  }

  // ---------- Module 4: requests ----------
  @PostMapping("/requests") public Map<String, Object> request(@RequestHeader("X-Token") String t, @RequestBody Map<String, Object> b) {
    var u = verified(t);
    int bookId = num(b.get("bookId")), me = num(u.get("id"));
    var l = db.queryForList("SELECT * FROM books WHERE id=?", bookId);
    if (l.isEmpty()) throw err(404, "Book not found");
    var book = l.get(0);
    if (num(book.get("owner_id")) == me) throw err(400, "This is your own listing");
    if (!"AVAILABLE".equals(book.get("availability"))) throw err(409, "This book is no longer available");
    if (!db.queryForList("SELECT id FROM requests WHERE book_id=? AND requester_id=? AND status='PENDING'", bookId, me).isEmpty()) throw err(409, "You already have a pending request for this book");
    db.update("INSERT INTO requests(book_id,requester_id,type) VALUES(?,?,?)", bookId, me, book.get("type"));
    notify(num(book.get("owner_id")), u.get("name") + " requested \"" + book.get("title") + "\"");
    return OK;
  }
  @GetMapping("/requests") public Map<String, Object> requests(@RequestHeader("X-Token") String t) {
    int id = num(user(t).get("id"));
    String base = "SELECT r.id,r.type,r.status,b.title,s.id other_id,s.name other FROM requests r JOIN books b ON b.id=r.book_id JOIN students s ON s.id=";
    return Map.of("incoming", db.queryForList(base + "r.requester_id WHERE b.owner_id=? ORDER BY r.id DESC", id),
                  "outgoing", db.queryForList(base + "b.owner_id WHERE r.requester_id=? ORDER BY r.id DESC", id));
  }
  @Transactional @PostMapping("/requests/{id}/respond")
  public Map<String, Object> respond(@RequestHeader("X-Token") String t, @PathVariable int id, @RequestBody Map<String, Object> b) {
    int me = num(user(t).get("id"));
    var l = db.queryForList("SELECT r.id,r.book_id,r.requester_id,r.type,r.status,b.owner_id,b.title FROM requests r JOIN books b ON b.id=r.book_id WHERE r.id=? FOR UPDATE", id);
    if (l.isEmpty() || num(l.get(0).get("owner_id")) != me) throw err(404, "Request not found");
    var r = l.get(0);
    if (!"PENDING".equals(r.get("status"))) throw err(409, "Already answered");
    int bookId = num(r.get("book_id")), req = num(r.get("requester_id"));
    if (!Boolean.TRUE.equals(b.get("accept"))) {
      db.update("UPDATE requests SET status='REJECTED' WHERE id=?", id);
      notify(req, "Your request for \"" + r.get("title") + "\" was declined");
      return OK;
    }
    String type = (String) r.get("type");
    // Conditional update: only one request can ever take an available book.
    int n = db.update("UPDATE books SET availability=? WHERE id=? AND availability='AVAILABLE'",
        type.equals("BORROW") ? "BORROWED" : type.equals("EXCHANGE") ? "EXCHANGED" : "SOLD", bookId);
    if (n == 0) throw err(409, "This book was already taken");
    db.update("UPDATE requests SET status='ACCEPTED' WHERE id=?", id);
    db.update("UPDATE requests SET status='REJECTED' WHERE book_id=? AND status='PENDING'", bookId);
    if (type.equals("BORROW")) {
      int days = b.get("days") == null ? 14 : num(b.get("days"));
      db.update("INSERT INTO borrowings(book_id,owner_id,borrower_id,borrow_date,due_date) VALUES(?,?,?,CURDATE(),DATE_ADD(CURDATE(),INTERVAL ? DAY))", bookId, me, req, days);
    }
    notify(req, "Your request for \"" + r.get("title") + "\" was accepted. Contact the owner to arrange the handover.");
    return OK;
  }

  // ---------- Module 5: borrowing + reminders ----------
  @GetMapping("/borrowings") public List<Map<String, Object>> borrowings(@RequestHeader("X-Token") String t) {
    int id = num(user(t).get("id"));
    return db.queryForList("SELECT w.id,b.title,o.name owner,r.name borrower,w.owner_id,w.borrower_id,w.borrow_date,w.due_date,w.returned,(w.returned=0 AND w.due_date<CURDATE()) overdue FROM borrowings w JOIN books b ON b.id=w.book_id JOIN students o ON o.id=w.owner_id JOIN students r ON r.id=w.borrower_id WHERE w.owner_id=? OR w.borrower_id=? ORDER BY w.id DESC", id, id);
  }
  @Transactional @PostMapping("/borrowings/{id}/return") public Map<String, Object> returned(@RequestHeader("X-Token") String t, @PathVariable int id) {
    int me = num(user(t).get("id"));
    if (db.update("UPDATE borrowings SET returned=1 WHERE id=? AND owner_id=? AND returned=0", id, me) == 0) throw err(404, "Borrowing not found");
    db.update("UPDATE books SET availability='AVAILABLE' WHERE id=(SELECT book_id FROM borrowings WHERE id=?)", id);
    return OK;
  }
  /** Runs at startup and every hour: one reminder 2 days before and one 1 day before the due date. */
  @Scheduled(fixedRate = 3600000, initialDelay = 10000) void remind() {
    for (String f : new String[]{"remind2", "remind1"}) {
      int days = f.equals("remind2") ? 2 : 1;
      db.update("INSERT INTO notifications(recipient_id,message) SELECT w.borrower_id,CONCAT('Reminder: return \"',b.title,'\" in ',DATEDIFF(w.due_date,CURDATE()),' day(s) (due ',w.due_date,')') FROM borrowings w JOIN books b ON b.id=w.book_id WHERE w.returned=0 AND w." + f + "=0 AND w.due_date>=CURDATE() AND w.due_date<=DATE_ADD(CURDATE(),INTERVAL ? DAY)", days);
      db.update("UPDATE borrowings SET " + f + "=1 WHERE returned=0 AND " + f + "=0 AND due_date>=CURDATE() AND due_date<=DATE_ADD(CURDATE(),INTERVAL ? DAY)", days);
    }
  }

  // ---------- Module 6: notifications, reviews, reports ----------
  @GetMapping("/notifications") public List<Map<String, Object>> notifications(@RequestHeader("X-Token") String t) {
    int id = num(user(t).get("id"));
    var l = db.queryForList("SELECT message,is_read,created FROM notifications WHERE recipient_id=? ORDER BY id DESC LIMIT 50", id);
    db.update("UPDATE notifications SET is_read=1 WHERE recipient_id=?", id);
    return l;
  }
  @PostMapping("/reviews") public Map<String, Object> review(@RequestHeader("X-Token") String t, @RequestBody Map<String, Object> b) {
    int me = num(verified(t).get("id")), to = num(b.get("userId")), r = num(b.get("rating"));
    if (me == to || r < 1 || r > 5) throw err(400, "Rating must be 1 to 5 for another student");
    if (!deal(me, to)) throw err(403, "You can review someone after a completed deal with them");
    db.update("INSERT INTO reviews(reviewer_id,reviewed_id,rating,feedback) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE rating=?,feedback=?,created=NOW()", me, to, r, b.get("feedback"), r, b.get("feedback"));
    notify(to, "You received a new review");
    return OK;
  }
  @PostMapping("/reports") public Map<String, Object> report(@RequestHeader("X-Token") String t, @RequestBody Map<String, Object> b) {
    db.update("INSERT INTO reports(reporter_id,book_id,reason) VALUES(?,?,?)", user(t).get("id"), num(b.get("bookId")), b.get("reason"));
    return OK;
  }

  // ---------- Profiles ----------
  boolean deal(int a, int b) {
    return !db.queryForList("SELECT r.id FROM requests r JOIN books k ON k.id=r.book_id WHERE r.status='ACCEPTED' AND ((r.requester_id=? AND k.owner_id=?) OR (r.requester_id=? AND k.owner_id=?))", a, b, b, a).isEmpty();
  }
  @GetMapping("/profile/{id}") public Map<String, Object> profile(@RequestHeader("X-Token") String t, @PathVariable int id) {
    int me = num(user(t).get("id"));
    var l = db.queryForList("SELECT id,name,department,semester,phone,status FROM students WHERE id=?", id);
    if (l.isEmpty()) throw err(404, "Profile not found");
    var p = new HashMap<String, Object>(l.get(0));
    boolean hadDeal = me != id && deal(me, id);
    if (me != id && !hadDeal) p.remove("phone"); // phone is shared only with yourself and people you have dealt with
    p.put("deal", hadDeal);
    var s = db.queryForMap("SELECT COUNT(*) n,COALESCE(ROUND(AVG(rating),1),0) average FROM reviews WHERE reviewed_id=?", id);
    p.put("count", s.get("n")); p.put("avg", s.get("average"));
    p.put("listings", db.queryForObject("SELECT COUNT(*) FROM books WHERE owner_id=? AND availability='AVAILABLE'", Integer.class, id));
    p.put("deals", db.queryForObject("SELECT COUNT(*) FROM requests r JOIN books k ON k.id=r.book_id WHERE r.status='ACCEPTED' AND (r.requester_id=? OR k.owner_id=?)", Integer.class, id, id));
    var mine = db.queryForList("SELECT rating,feedback FROM reviews WHERE reviewer_id=? AND reviewed_id=?", me, id);
    p.put("mine", mine.isEmpty() ? null : mine.get(0));
    p.put("reviews", db.queryForList("SELECT v.rating,v.feedback,v.created,s.name reviewer,s.id reviewer_id FROM reviews v JOIN students s ON s.id=v.reviewer_id WHERE v.reviewed_id=? ORDER BY v.id DESC", id));
    return p;
  }
  @PutMapping("/profile") public Map<String, Object> updateProfile(@RequestHeader("X-Token") String t, @RequestBody Map<String, String> b) {
    db.update("UPDATE students SET name=?,phone=?,department=?,semester=? WHERE id=?", b.get("name"), b.get("phone"), b.get("department"), Integer.parseInt(b.get("semester")), user(t).get("id"));
    return OK;
  }

  // ---------- Module 7: admin ----------
  @GetMapping("/admin/pending") public List<Map<String, Object>> pending(@RequestHeader("X-Token") String t) {
    admin(t);
    return db.queryForList("SELECT id,name,email,department,semester,id_card FROM students WHERE status='PENDING' AND role='STUDENT'");
  }
  @PostMapping("/admin/verify/{id}") public Map<String, Object> verify(@RequestHeader("X-Token") String t, @PathVariable int id, @RequestBody Map<String, Object> b) {
    admin(t);
    boolean ok = Boolean.TRUE.equals(b.get("ok"));
    db.update("UPDATE students SET status=? WHERE id=?", ok ? "VERIFIED" : "REJECTED", id);
    notify(id, ok ? "Your college ID was verified. You can now list and request books." : "Your college ID could not be verified. Please contact the admin.");
    return OK;
  }
  @GetMapping("/admin/reports") public List<Map<String, Object>> reports(@RequestHeader("X-Token") String t) {
    admin(t);
    return db.queryForList("SELECT r.id,r.reason,b.id book_id,b.title,s.name reporter FROM reports r JOIN books b ON b.id=r.book_id JOIN students s ON s.id=r.reporter_id WHERE r.resolved=0");
  }
  @PostMapping("/admin/reports/{id}/resolve") public Map<String, Object> resolve(@RequestHeader("X-Token") String t, @PathVariable int id, @RequestBody Map<String, Object> b) {
    admin(t);
    if (Boolean.TRUE.equals(b.get("remove"))) db.update("UPDATE books SET availability='REMOVED' WHERE id=(SELECT book_id FROM reports WHERE id=?)", id);
    db.update("UPDATE reports SET resolved=1 WHERE id=?", id);
    return OK;
  }
  @GetMapping("/admin/stats") public Map<String, Object> stats(@RequestHeader("X-Token") String t) {
    admin(t);
    return db.queryForMap("SELECT (SELECT COUNT(*) FROM students WHERE role='STUDENT') students,(SELECT COUNT(*) FROM books WHERE availability<>'REMOVED') books,(SELECT COUNT(*) FROM requests WHERE status='ACCEPTED') completed,(SELECT COUNT(*) FROM borrowings WHERE returned=0 AND due_date<CURDATE()) overdue");
  }
}
