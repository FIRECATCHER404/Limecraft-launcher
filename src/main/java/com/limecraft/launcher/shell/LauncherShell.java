package com.limecraft.launcher.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class LauncherShell {
    private static final double RESIZE_BORDER = 6;
    private static final double CORNER_RESIZE_SIZE = 12;

    private final Pane root;
    private final HBox topBar;
    private final Button updateChip;
    private final Label jobsChip;
    private final Button errorChip;
    private final Label accountChip;
    private final Button maximizeButton;
    private double dragOffsetX;
    private double dragOffsetY;
    private Cursor activeResizeCursor = Cursor.DEFAULT;
    private boolean resizeActive;
    private double resizeStartScreenX;
    private double resizeStartScreenY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;
    private boolean maximized;
    private double restoreX = Double.NaN;
    private double restoreY = Double.NaN;
    private double restoreWidth = Double.NaN;
    private double restoreHeight = Double.NaN;

    public LauncherShell(Stage stage, String title, Node content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("topbar-title");

        updateChip = createChipButton("Update");
        updateChip.setVisible(false);
        updateChip.setManaged(false);

        jobsChip = createChipLabel("Idle");
        applyChipState(jobsChip, "topbar-chip-idle");

        errorChip = createChipButton("Errors");
        errorChip.setVisible(false);
        errorChip.setManaged(false);

        accountChip = createChipLabel("Offline");
        applyChipState(accountChip, "topbar-chip-account");

        HBox statusChips = new HBox(8, updateChip, jobsChip, errorChip, accountChip);
        statusChips.setAlignment(Pos.CENTER_LEFT);

        Button minimizeButton = createWindowButton("-");
        minimizeButton.setOnAction(event -> stage.setIconified(true));

        maximizeButton = createWindowButton("[ ]");
        maximizeButton.setOnAction(event -> toggleMaximized(stage));

        Button closeButton = createWindowButton("X");
        closeButton.getStyleClass().add("window-button-close");
        closeButton.setOnAction(event -> stage.close());

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        HBox windowButtons = new HBox(6, minimizeButton, maximizeButton, closeButton);
        windowButtons.setAlignment(Pos.CENTER_RIGHT);

        topBar = new HBox(14, titleLabel, leftSpacer, statusChips, rightSpacer, windowButtons);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 12, 10, 14));
        topBar.getStyleClass().add("custom-topbar");

        VBox contentBox = new VBox(content);
        contentBox.getStyleClass().add("shell-content");
        VBox.setVgrow(contentBox, Priority.ALWAYS);
        if (content instanceof Region region) {
            region.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            VBox.setVgrow(region, Priority.ALWAYS);
        }

        VBox shellContent = new VBox(topBar, contentBox);
        VBox.setVgrow(contentBox, Priority.ALWAYS);

        Region leftHandle = createResizeHandle(stage, Cursor.W_RESIZE);
        Region rightHandle = createResizeHandle(stage, Cursor.E_RESIZE);
        Region topHandle = createResizeHandle(stage, Cursor.N_RESIZE);
        Region bottomHandle = createResizeHandle(stage, Cursor.S_RESIZE);
        Region northWestHandle = createResizeHandle(stage, Cursor.NW_RESIZE);
        Region northEastHandle = createResizeHandle(stage, Cursor.NE_RESIZE);
        Region southWestHandle = createResizeHandle(stage, Cursor.SW_RESIZE);
        Region southEastHandle = createResizeHandle(stage, Cursor.SE_RESIZE);

        root = new Pane() {
            @Override
            protected void layoutChildren() {
                double width = getWidth();
                double height = getHeight();
                shellContent.resizeRelocate(0, 0, width, height);

                leftHandle.resizeRelocate(0, 0, RESIZE_BORDER, height);
                rightHandle.resizeRelocate(Math.max(0, width - RESIZE_BORDER), 0, RESIZE_BORDER, height);
                topHandle.resizeRelocate(0, 0, width, RESIZE_BORDER);
                bottomHandle.resizeRelocate(0, Math.max(0, height - RESIZE_BORDER), width, RESIZE_BORDER);

                northWestHandle.resizeRelocate(0, 0, CORNER_RESIZE_SIZE, CORNER_RESIZE_SIZE);
                northEastHandle.resizeRelocate(Math.max(0, width - CORNER_RESIZE_SIZE), 0, CORNER_RESIZE_SIZE, CORNER_RESIZE_SIZE);
                southWestHandle.resizeRelocate(0, Math.max(0, height - CORNER_RESIZE_SIZE), CORNER_RESIZE_SIZE, CORNER_RESIZE_SIZE);
                southEastHandle.resizeRelocate(Math.max(0, width - CORNER_RESIZE_SIZE), Math.max(0, height - CORNER_RESIZE_SIZE), CORNER_RESIZE_SIZE, CORNER_RESIZE_SIZE);
            }
        };
        root.getStyleClass().add("shell-root");
        root.setPickOnBounds(true);
        shellContent.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        root.getChildren().addAll(
                shellContent,
                leftHandle,
                rightHandle,
                topHandle,
                bottomHandle,
                northWestHandle,
                northEastHandle,
                southWestHandle,
                southEastHandle
        );

        wireWindowDragging(stage);
    }

    public Parent root() {
        return root;
    }

    public void setUpdateAction(Runnable action) {
        updateChip.setOnAction(event -> {
            if (action != null) {
                action.run();
            }
        });
    }

    public void setErrorAction(Runnable action) {
        errorChip.setOnAction(event -> {
            if (action != null) {
                action.run();
            }
        });
    }

    public void setUpdateStatus(String text, String tooltip, boolean visible) {
        updateChip.setText(text == null || text.isBlank() ? "Update" : text.trim());
        updateChip.setVisible(visible);
        updateChip.setManaged(visible);
        applyChipState(updateChip, visible ? "topbar-chip-ready" : "topbar-chip-idle");
        updateChip.setTooltip(tooltip == null || tooltip.isBlank() ? null : new Tooltip(tooltip));
    }

    public void setJobStatus(String text, String tooltip, boolean active) {
        jobsChip.setText(text == null || text.isBlank() ? "Idle" : text.trim());
        applyChipState(jobsChip, active ? "topbar-chip-active" : "topbar-chip-idle");
        jobsChip.setTooltip(tooltip == null || tooltip.isBlank() ? null : new Tooltip(tooltip));
    }

    public void setErrorStatus(String text, String tooltip, boolean visible) {
        errorChip.setText(text == null || text.isBlank() ? "Errors" : text.trim());
        errorChip.setVisible(visible);
        errorChip.setManaged(visible);
        applyChipState(errorChip, visible ? "topbar-chip-alert" : "topbar-chip-idle");
        errorChip.setTooltip(tooltip == null || tooltip.isBlank() ? null : new Tooltip(tooltip));
    }

    public void setAccountStatus(String text, String tooltip, boolean activeSession) {
        accountChip.setText(text == null || text.isBlank() ? "Offline" : text.trim());
        applyChipState(accountChip, activeSession ? "topbar-chip-account" : "topbar-chip-idle");
        accountChip.setTooltip(tooltip == null || tooltip.isBlank() ? null : new Tooltip(tooltip));
    }

    private Button createChipButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("topbar-chip", "topbar-chip-button");
        button.setFocusTraversable(false);
        return button;
    }

    private Label createChipLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("topbar-chip");
        label.setAlignment(Pos.CENTER_LEFT);
        return label;
    }

    private Button createWindowButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("window-button");
        button.setFocusTraversable(false);
        button.setMinWidth(44);
        button.setPrefWidth(44);
        return button;
    }

    private void applyChipState(Labeled labeled, String stateClass) {
        labeled.getStyleClass().removeAll(
                "topbar-chip-ready",
                "topbar-chip-active",
                "topbar-chip-alert",
                "topbar-chip-account",
                "topbar-chip-idle"
        );
        if (stateClass != null && !stateClass.isBlank()) {
            labeled.getStyleClass().add(stateClass);
        }
    }

    private void wireWindowDragging(Stage stage) {
        topBar.setOnMousePressed(event -> {
            if (resizeActive || activeResizeCursor != Cursor.DEFAULT) {
                return;
            }
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });
        topBar.setOnMouseDragged(event -> {
            if (isWindowMaximized() || resizeActive || activeResizeCursor != Cursor.DEFAULT) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
        topBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggleMaximized(stage);
            }
        });
    }

    private Region createResizeHandle(Stage stage, Cursor cursor) {
        Region handle = new Region();
        handle.getStyleClass().add("resize-handle");
        handle.setManaged(false);
        handle.setPickOnBounds(true);

        handle.addEventFilter(MouseEvent.MOUSE_ENTERED, event -> {
            if (!resizeActive) {
                updateResizeCursor(cursor);
            }
        });
        handle.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (!resizeActive) {
                updateResizeCursor(cursor);
            }
        });
        handle.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (isWindowMaximized()) {
                resizeActive = false;
                updateResizeCursor(Cursor.DEFAULT);
                return;
            }
            activeResizeCursor = cursor;
            resizeActive = true;
            resizeStartScreenX = event.getScreenX();
            resizeStartScreenY = event.getScreenY();
            resizeStartX = stage.getX();
            resizeStartY = stage.getY();
            resizeStartWidth = stage.getWidth();
            resizeStartHeight = stage.getHeight();
            event.consume();
        });
        handle.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!resizeActive || isWindowMaximized()) {
                return;
            }
            applyResize(stage, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        handle.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!resizeActive) {
                return;
            }
            resizeActive = false;
            updateResizeCursor(cursor);
            event.consume();
        });
        handle.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (!resizeActive) {
                updateResizeCursor(Cursor.DEFAULT);
            }
        });
        return handle;
    }

    private void applyResize(Stage stage, double screenX, double screenY) {
        double deltaX = screenX - resizeStartScreenX;
        double deltaY = screenY - resizeStartScreenY;
        double minWidth = Math.max(stage.getMinWidth(), 920);
        double minHeight = Math.max(stage.getMinHeight(), 620);

        double newX = resizeStartX;
        double newY = resizeStartY;
        double newWidth = resizeStartWidth;
        double newHeight = resizeStartHeight;

        if (activeResizeCursor == Cursor.E_RESIZE || activeResizeCursor == Cursor.NE_RESIZE || activeResizeCursor == Cursor.SE_RESIZE) {
            newWidth = Math.max(minWidth, resizeStartWidth + deltaX);
        }
        if (activeResizeCursor == Cursor.W_RESIZE || activeResizeCursor == Cursor.NW_RESIZE || activeResizeCursor == Cursor.SW_RESIZE) {
            newWidth = Math.max(minWidth, resizeStartWidth - deltaX);
            newX = resizeStartX + (resizeStartWidth - newWidth);
        }
        if (activeResizeCursor == Cursor.S_RESIZE || activeResizeCursor == Cursor.SE_RESIZE || activeResizeCursor == Cursor.SW_RESIZE) {
            newHeight = Math.max(minHeight, resizeStartHeight + deltaY);
        }
        if (activeResizeCursor == Cursor.N_RESIZE || activeResizeCursor == Cursor.NE_RESIZE || activeResizeCursor == Cursor.NW_RESIZE) {
            newHeight = Math.max(minHeight, resizeStartHeight - deltaY);
            newY = resizeStartY + (resizeStartHeight - newHeight);
        }

        stage.setX(newX);
        stage.setY(newY);
        stage.setWidth(newWidth);
        stage.setHeight(newHeight);
    }

    private void toggleMaximized(Stage stage) {
        if (isWindowMaximized()) {
            restoreWindow(stage);
        } else {
            maximizeWindow(stage);
        }
        updateResizeCursor(Cursor.DEFAULT);
        resizeActive = false;
    }

    private boolean isWindowMaximized() {
        return maximized;
    }

    private void maximizeWindow(Stage stage) {
        rememberRestoreBounds(stage);
        Rectangle2D bounds = resolveVisualBounds(stage);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        maximized = true;
        updateMaximizeButton();
    }

    private void restoreWindow(Stage stage) {
        if (Double.isFinite(restoreX) && Double.isFinite(restoreY)
                && Double.isFinite(restoreWidth) && Double.isFinite(restoreHeight)) {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
        }
        maximized = false;
        updateMaximizeButton();
    }

    private void rememberRestoreBounds(Stage stage) {
        restoreX = stage.getX();
        restoreY = stage.getY();
        restoreWidth = stage.getWidth();
        restoreHeight = stage.getHeight();
    }

    private Rectangle2D resolveVisualBounds(Stage stage) {
        double width = Math.max(stage.getWidth(), 1);
        double height = Math.max(stage.getHeight(), 1);
        Screen screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), width, height)
                .stream()
                .findFirst()
                .orElse(Screen.getPrimary());
        return screen.getVisualBounds();
    }

    private void updateMaximizeButton() {
        maximizeButton.setText(maximized ? "[] " : "[ ]");
    }

    private void updateResizeCursor(Cursor cursor) {
        Cursor resolved = cursor == null ? Cursor.DEFAULT : cursor;
        root.setCursor(resolved);
        Scene scene = root.getScene();
        if (scene != null) {
            scene.setCursor(resolved);
        }
        activeResizeCursor = resolved;
    }
}
