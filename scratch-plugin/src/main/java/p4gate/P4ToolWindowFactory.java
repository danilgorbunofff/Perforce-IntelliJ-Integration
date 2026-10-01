package p4gate;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Registers the "Perforce P4" tool window: changelist tree + the connect-and-explain diagnostics tab, both driven by the p4 CLI. */
public final class P4ToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        P4Panel panel = new P4Panel();
        Content content = ContentFactory.getInstance().createContent(panel.root(), "Changelists", false);
        toolWindow.getContentManager().addContent(content);

        P4Connect connect = new P4Connect();
        Content connection = ContentFactory.getInstance().createContent(connect.root(), "Connection", false);
        toolWindow.getContentManager().addContent(connection);

        P4StreamsPanel streams = new P4StreamsPanel();
        Content streamTab = ContentFactory.getInstance().createContent(streams.root(), "Streams", false);
        toolWindow.getContentManager().addContent(streamTab);
    }
}
