package tracker.db;

import tracker.model.Models.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import static tracker.db.Database.*;

public final class Tasks {
    private final Database db;
    public Tasks(Database db) { this.db = db; }

    public List<Task> all() throws SQLException {
        return db.read(c -> query(c, """
            SELECT * FROM tasks ORDER BY completed,due IS NULL,due,
                CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,id
            """, r -> new Task(r.getLong("id"), r.getString("title"), r.getString("category"),
                Priority.valueOf(r.getString("priority")), r.getString("due") == null ? null :
                LocalDate.parse(r.getString("due")), r.getBoolean("completed"))));
    }

    public void save(Task task) throws SQLException {
        if (task.title().isBlank()) throw new IllegalArgumentException("Enter a task title.");
        db.write(c -> {
            Object due = task.due() == null ? null : task.due().toString();
            if (task.id() == 0) update(c, "INSERT INTO tasks(title,category,priority,due,completed) VALUES (?,?,?,?,?)",
                    task.title(), task.category(), task.priority().name(), due, task.completed());
            else update(c, "UPDATE tasks SET title=?,category=?,priority=?,due=?,completed=? WHERE id=?",
                    task.title(), task.category(), task.priority().name(), due, task.completed(), task.id());
            return null;
        });
    }

    public void delete(long id) throws SQLException {
        db.write(c -> update(c, "DELETE FROM tasks WHERE id=?", id));
    }
}
