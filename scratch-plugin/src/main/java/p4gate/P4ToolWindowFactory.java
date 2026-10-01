package p4gate;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Registers the "Perforce P4" tool window. DumbAware: p4 work does not need the index, so it stays usable while indexing.
 *  All tabs share the project's {@link P4Project} — one executable, one workspace dir, per project. */
public final class P4ToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        P4Project service = P4Project.get(project);
        ContentFactory cf = ContentFactory.getInstance();

        P4Panel panel = new P4Panel(service);
        toolWindow.getContentManager().addContent(cf.createContent(panel.root(), "Changelists", false));

        P4Connect connect = new P4Connect(service);
        toolWindow.getContentManager().addContent(cf.createContent(connect.root(), "Connection", false));

        P4StreamsPanel streams = new P4StreamsPanel(service);
        Content streamTab = cf.createContent(streams.root(), "Streams", false);
        toolWindow.getContentManager().addContent(streamTab);
    }
}
