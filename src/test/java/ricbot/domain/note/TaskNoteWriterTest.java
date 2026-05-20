package ricbot.domain.note;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.agent.TaskSummaryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskNoteWriterTest {

    @Test
    void rendersTaskSummaryAsMarkdown(@TempDir Path workspace) {
        TaskNoteWriter writer = new TaskNoteWriter(new NoteService(workspace));
        TaskSummaryService.TaskSummary summary = summary();

        String markdown = writer.renderMarkdown(summary);

        assertTrue(markdown.contains("# Task Summary - V3.4 task notes"), markdown);
        assertTrue(markdown.contains("## Changed Files"), markdown);
        assertTrue(markdown.contains("src/main/java/ricbot/domain/note/TaskNoteWriter.java"), markdown);
        assertTrue(markdown.contains("## Approval Records"), markdown);
        assertTrue(markdown.contains("approval_abc123"), markdown);
        assertTrue(markdown.contains("## Rollback Hints"), markdown);
        assertTrue(markdown.contains("## Team Findings"), markdown);
        assertTrue(markdown.contains("team team_demo state=VERIFYING"), markdown);
        assertTrue(markdown.contains("## Verifier Report"), markdown);
        assertTrue(markdown.contains("status=REJECT"), markdown);
        assertTrue(markdown.contains("## Worker Findings"), markdown);
        assertTrue(markdown.contains("Explorer summarized workspace context"), markdown);
        assertTrue(markdown.contains("## Workspace"), markdown);
        assertTrue(markdown.contains(".workspaces/workspace_demo/session.json"), markdown);
        assertTrue(markdown.contains("## SubAgent Findings"), markdown);
        assertTrue(markdown.contains("PLANNER task=subtask_demo"), markdown);
        assertTrue(markdown.contains("## Trace Summary"), markdown);
        assertTrue(markdown.contains(".traces/trace_cli_direct/events.jsonl"), markdown);
    }

    @Test
    void writesTaskSummaryIntoTasksAndUpdatesIndex(@TempDir Path workspace) throws Exception {
        NoteService noteService = new NoteService(workspace);
        TaskNoteWriter writer = new TaskNoteWriter(noteService);

        TaskNoteWriter.WriteResult result = writer.write(summary(), "tasks");

        assertEquals("tasks", result.category());
        assertTrue(result.path().startsWith("notes/tasks/"), result.path());
        assertTrue(result.path().contains("task_summary_v3_4_task_notes"), result.path());
        assertTrue(Files.exists(workspace.resolve(result.path())));
        String index = Files.readString(workspace.resolve("notes").resolve("index.json"));
        assertTrue(index.contains(result.noteId()), index);
    }

    @Test
    void writtenTaskNoteCanBeSearched(@TempDir Path workspace) {
        NoteService noteService = new NoteService(workspace);
        TaskNoteWriter writer = new TaskNoteWriter(noteService);
        writer.write(summary(), "tasks");

        List<NoteService.SearchResult> results = noteService.search("rollback approval V3.4", 5);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).entry().path().startsWith("notes/tasks/"));
    }

    private TaskSummaryService.TaskSummary summary() {
        return new TaskSummaryService.TaskSummary(
                "V3.4 task notes",
                List.of("src/main/java/ricbot/domain/note/TaskNoteWriter.java"),
                List.of("Task notes are written only on explicit /summary --write-note"),
                List.of("./mvnw -q -Dtest='ricbot.domain.note.*Test' test"),
                List.of(),
                List.of("Run targeted tests"),
                List.of("write_file: requestId=approval_abc123, riskLevel=MEDIUM"),
                List.of("TaskNoteWriter.java — Created task note writer [risk=MEDIUM]"),
                List.of("./mvnw -q -Dtest='ricbot.domain.note.*Test' test"),
                List.of("git checkout -- src/main/java/ricbot/domain/note/TaskNoteWriter.java"),
                List.of("team team_demo state=VERIFYING goal=V3.4 task notes"),
                List.of("task=teamtask_demo | status=REJECT | missingTests=./mvnw -q test | requiredActions=Run tests"),
                List.of("task=teamtask_demo | role=EXPLORER | status=COMPLETED | summary=Explorer summarized workspace context"),
                List.of(),
                List.of("workspace workspace_demo | type: GIT_WORKTREE | status: ACTIVE", "source=.workspaces/workspace_demo/session.json"),
                "",
                "",
                "",
                List.of("PLANNER task=subtask_demo summary=Plan note writing"),
                "trace trace_cli_direct\npath: .traces/trace_cli_direct/events.jsonl\neventCount: 3",
                ""
        );
    }
}
