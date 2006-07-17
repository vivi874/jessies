package e.edit;

/**
 * Runs RevisionTool.
 */
public class ShowHistoryAction extends ExternalToolAction {
    public ShowHistoryAction() {
        super("Show History...", ToolInputDisposition.NO_INPUT, ToolOutputDisposition.ERRORS_WINDOW, "revisiontool $EDIT_CURRENT_FILENAME:$EDIT_CURRENT_LINE_NUMBER");
        putValue(ACCELERATOR_KEY, e.util.GuiUtilities.makeKeyStroke("H", true));
        setNeedsFile(true);
    }
}
