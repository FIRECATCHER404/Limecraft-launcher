package com.limecraft.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limecraft.launcher.auth.DeviceCodeInfo;
import com.limecraft.launcher.auth.MicrosoftAuthService;
import com.limecraft.launcher.auth.MinecraftAccount;
import com.limecraft.launcher.core.HttpClient;
import com.limecraft.launcher.core.MinecraftInstallService;
import com.limecraft.launcher.core.MinecraftLaunchService;
import com.limecraft.launcher.core.VersionEntry;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LimecraftApp extends Application {
    private static final String MODE_MICROSOFT = "Microsoft";
    private static final String MODE_OFFLINE = "Offline";
    private static final String SETTINGS_FILE = "launcher.properties";
    private static final String KEY_LAST_VERSION = "last_selected_version";
    private static final String KEY_OFFLINE_USERNAME = "offline_username";
    private static final String KEY_ACCOUNT_MODE = "account_mode";

    private final ExecutorService io = Executors.newFixedThreadPool(4);

    private final Path gameDir = Path.of(System.getProperty("user.home"), ".limecraft");
    private final HttpClient http = new HttpClient();
    private final MinecraftInstallService installService = new MinecraftInstallService(http, gameDir);
    private final MinecraftLaunchService launchService = new MinecraftLaunchService(gameDir);

    private MinecraftAccount signedInAccount;

    private ListView<VersionEntry> versionsList;
    private Label status;
    private TextArea logOutput;
    private ProgressBar progress;
    private Label accountLabel;
    private TextField javaPathField;
    private TextField ramField;
    private TextField clientIdField;
    private ComboBox<String> accountModeBox;
    private TextField offlineUsernameField;
    private TextField searchField;
    private CheckBox experimentToggle;
    private Button signInButton;
    private Button detectJavaButton;
    private Button launchButton;
    private Label selectedVersionLabel;
    private Process currentGameProcess;
    private List<Control> controlsToDisableOnRun = List.of();
    private List<VersionEntry> allVersions = List.of();
    private String lastSelectedVersionId;
    private String savedOfflineUsername = "Player";
    private String savedAccountMode = MODE_MICROSOFT;

    @Override
    public void start(Stage stage) {
        loadSettings();

        versionsList = new ListView<>();
        versionsList.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        status = new Label("Ready");
        logOutput = new TextArea();
        logOutput.setEditable(false);
        logOutput.setWrapText(true);
        logOutput.setPrefRowCount(8);
        logOutput.setPromptText("Game log output will appear here...");
        logOutput.getStyleClass().add("console-log");
        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);

        accountLabel = new Label("Not signed in");
        javaPathField = new TextField("java");
        detectJavaButton = new Button("Detect Java");
        detectJavaButton.setOnAction(e -> openJavaDetector());
        ramField = new TextField("4G");
        clientIdField = new TextField(MicrosoftAuthService.DEFAULT_CLIENT_ID);
        offlineUsernameField = new TextField(savedOfflineUsername);
        offlineUsernameField.textProperty().addListener((obs, oldVal, newVal) -> {
            String trimmed = newVal == null ? "" : newVal.trim();
            savedOfflineUsername = trimmed.isBlank() ? "Player" : trimmed;
            saveSettings();
        });
        accountModeBox = new ComboBox<>(FXCollections.observableArrayList(MODE_MICROSOFT, MODE_OFFLINE));
        accountModeBox.getSelectionModel().select(savedAccountMode);
        if (accountModeBox.getValue() == null) {
            accountModeBox.getSelectionModel().selectFirst();
        }
        accountModeBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                savedAccountMode = newVal;
                saveSettings();
            }
        });
        searchField = new TextField();
        searchField.setPromptText("Search a version...");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyVersionFilter());

        experimentToggle = new CheckBox("Show Experimental");
        experimentToggle.setSelected(true);
        experimentToggle.selectedProperty().addListener((obs, oldVal, newVal) -> applyVersionFilter());

        signInButton = new Button("Sign In (Microsoft)");
        signInButton.setOnAction(e -> signIn());

        launchButton = new Button("Launch");
        launchButton.getStyleClass().add("launch-button");
        launchButton.setOnAction(e -> launchSelected());

        selectedVersionLabel = new Label("Selected: none");
        selectedVersionLabel.getStyleClass().add("selected-version");

        VBox actionButtons = new VBox(8, launchButton, selectedVersionLabel);
        launchButton.setMaxWidth(Double.MAX_VALUE);
        signInButton.setMaxWidth(Double.MAX_VALUE);

        VBox microsoftAuthBox = new VBox(8,
                labeledNode("Microsoft Client ID", clientIdField),
                signInButton,
                accountLabel
        );
        VBox offlineAuthBox = new VBox(8,
                labeledNode("Offline Username", offlineUsernameField)
        );

        accountModeBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean microsoft = MODE_MICROSOFT.equals(newVal);
            setModeVisibility(microsoftAuthBox, offlineAuthBox, microsoft);
        });

        VBox left = new VBox(12,
                title("Limecraft"),
                labeledNode("Account Mode", accountModeBox),
                microsoftAuthBox,
                offlineAuthBox,
                new Separator(),
                labeledNode("Search Versions", searchField),
                experimentToggle,
                javaPathNode(),
                labeledNode("Max Memory (-Xmx)", ramField),
                actionButtons
        );
        setModeVisibility(microsoftAuthBox, offlineAuthBox, MODE_MICROSOFT.equals(accountModeBox.getValue()));
        left.setPrefWidth(340);
        left.setMinWidth(320);
        left.setMaxWidth(420);

        VBox right = new VBox(8, new Label("Minecraft Versions"), versionsList);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(versionsList, Priority.ALWAYS);
        right.setMaxWidth(Double.MAX_VALUE);

        HBox rootRow = new HBox(18, left, right);

        versionsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            selectedVersionLabel.setText(newVal == null ? "Selected: none" : "Selected: " + newVal.id());
            if (newVal != null) {
                lastSelectedVersionId = newVal.id();
                saveSettings();
            }
        });

        controlsToDisableOnRun = List.of(
                accountModeBox,
                clientIdField,
                signInButton,
                offlineUsernameField,
                searchField,
                experimentToggle,
                javaPathField,
                detectJavaButton,
                ramField,
                versionsList
        );

        VBox root = new VBox(12, rootRow, progress, status, logOutput);
        root.setPadding(new Insets(16));
        VBox.setVgrow(rootRow, Priority.ALWAYS);
        VBox.setVgrow(logOutput, Priority.SOMETIMES);

        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());

        stage.setTitle("Limecraft");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/limecraft.png")));
        stage.setScene(scene);
        stage.show();

        refreshVersions();
    }

    private Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("title");
        return l;
    }

    private VBox labeledNode(String label, Control control) {
        Label l = new Label(label);
        control.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(4, l, control);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private VBox javaPathNode() {
        Label label = new Label("Java Path");
        javaPathField.setMaxWidth(Double.MAX_VALUE);
        detectJavaButton.setMinWidth(110);
        HBox row = new HBox(8, javaPathField, detectJavaButton);
        HBox.setHgrow(javaPathField, Priority.ALWAYS);
        VBox box = new VBox(4, label, row);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private void openJavaDetector() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Select Java Runtime");

        TableView<JavaRuntimeOption> table = new TableView<>();
        table.getStyleClass().add("java-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<JavaRuntimeOption, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().version()));
        versionCol.setPrefWidth(120);

        TableColumn<JavaRuntimeOption, String> archCol = new TableColumn<>("Architecture");
        archCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().architecture()));
        archCol.setPrefWidth(110);

        TableColumn<JavaRuntimeOption, String> pathCol = new TableColumn<>("Path");
        pathCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().path()));
        pathCol.setPrefWidth(360);

        table.getColumns().addAll(versionCol, archCol, pathCol);

        Runnable refresh = () -> {
            List<JavaRuntimeOption> items = detectInstalledJavas();
            table.setItems(FXCollections.observableArrayList(items));
            if (!items.isEmpty()) {
                table.getSelectionModel().selectFirst();
            }
        };
        refresh.run();

        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                javaPathField.setText(table.getSelectionModel().getSelectedItem().path());
                dialog.close();
            }
        });

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refresh.run());

        Button okButton = new Button("OK");
        okButton.getStyleClass().add("launch-button");
        okButton.setOnAction(e -> {
            JavaRuntimeOption selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                javaPathField.setText(selected.path());
            }
            dialog.close();
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> dialog.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, refreshButton, spacer, okButton, cancelButton);
        buttons.getStyleClass().add("java-popup-buttons");

        VBox root = new VBox(10, table, buttons);
        root.getStyleClass().add("java-popup-root");
        root.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 620, 360);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private List<JavaRuntimeOption> detectInstalledJavas() {
        Path javaRoot = Path.of("C:/Program Files/Java");
        if (!Files.isDirectory(javaRoot)) {
            return List.of();
        }

        List<JavaRuntimeOption> found = new ArrayList<>();
        try (var stream = Files.list(javaRoot)) {
            stream.filter(Files::isDirectory).forEach(home -> {
                Path javaw = home.resolve("bin").resolve("javaw.exe");
                if (!Files.exists(javaw)) {
                    return;
                }
                String version = readJavaVersion(home);
                String arch = inferArchitecture(home);
                found.add(new JavaRuntimeOption(version, arch, javaw.toString()));
            });
        } catch (Exception ex) {
            appendLog("[Limecraft] Java detect failed: " + ex.getMessage());
        }

        found.sort(Comparator.comparing(JavaRuntimeOption::version).reversed());
        return found;
    }

    private String readJavaVersion(Path javaHome) {
        Path javaExe = javaHome.resolve("bin").resolve("java.exe");
        if (!Files.exists(javaExe)) {
            return javaHome.getFileName().toString();
        }
        try {
            Process process = new ProcessBuilder(javaExe.toString(), "-version").start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            process.getErrorStream().transferTo(output);
            process.waitFor();
            String text = output.toString(StandardCharsets.UTF_8);
            int firstQuote = text.indexOf('"');
            if (firstQuote >= 0) {
                int secondQuote = text.indexOf('"', firstQuote + 1);
                if (secondQuote > firstQuote) {
                    return text.substring(firstQuote + 1, secondQuote);
                }
            }
        } catch (Exception ignored) {
        }
        return javaHome.getFileName().toString();
    }

    private String inferArchitecture(Path javaHome) {
        String name = javaHome.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("x86") || name.contains("32")) {
            return "x86";
        }
        return "amd64";
    }

    private record JavaRuntimeOption(String version, String architecture, String path) {}

    private void setModeVisibility(VBox microsoftAuthBox, VBox offlineAuthBox, boolean microsoft) {
        microsoftAuthBox.setManaged(microsoft);
        microsoftAuthBox.setVisible(microsoft);
        offlineAuthBox.setManaged(!microsoft);
        offlineAuthBox.setVisible(!microsoft);
    }

    private void refreshVersions() {
        setStatus("Loading official version list...", 0.05);
        io.submit(() -> {
            try {
                List<VersionEntry> versions = installService.listVersions();
                Platform.runLater(() -> {
                    allVersions = versions;
                    applyVersionFilter();
                    setStatus("Loaded " + versions.size() + " versions", 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void signIn() {
        if (!MODE_MICROSOFT.equals(accountModeBox.getValue())) {
            setStatus("Switch Account Mode to Microsoft to sign in.", 0);
            return;
        }
        String clientId = clientIdField.getText().trim();
        if (clientId.isEmpty() || MicrosoftAuthService.DEFAULT_CLIENT_ID.equals(clientId)) {
            setStatus("Set a valid Azure App Client ID first", 0);
            return;
        }

        setStatus("Starting Microsoft device login...", 0.1);
        io.submit(() -> {
            try {
                MicrosoftAuthService auth = new MicrosoftAuthService(http, clientId);
                DeviceCodeInfo code = auth.beginDeviceLogin();

                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setHeaderText("Microsoft Sign-In");
                    alert.setTitle("Device Code Login");
                    alert.setContentText(code.message());
                    alert.show();
                });

                MinecraftAccount account = auth.completeDeviceLogin(code);
                if (!auth.hasGameOwnership(account.accessToken())) {
                    throw new IllegalStateException("Microsoft account does not own Minecraft Java Edition.");
                }

                signedInAccount = account;
                Platform.runLater(() -> {
                    accountLabel.setText("Signed in as " + account.username());
                    setStatus("Signed in successfully", 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void launchSelected() {
        if (isGameRunning()) {
            killRunningGame();
            return;
        }
        VersionEntry selected = versionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a version first", 0);
            return;
        }
        io.submit(() -> {
            try {
                Path versionJson = gameDir.resolve("versions").resolve(selected.id()).resolve(selected.id() + ".json");
                Path versionJar = gameDir.resolve("versions").resolve(selected.id()).resolve(selected.id() + ".jar");
                if (!Files.exists(versionJson) || !Files.exists(versionJar)) {
                    setStatus("Installing " + selected.id() + " before launch...", 0.15);
                    installService.installVersion(selected, this::setStatus);
                    setStatus("Installed " + selected.id(), 0.8);
                }
                JsonObject meta = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
                MinecraftAccount accountToUse = null;
                String offlineUsername = null;
                if (MODE_MICROSOFT.equals(accountModeBox.getValue())) {
                    if (signedInAccount == null) {
                        setStatus("Sign in with Microsoft first, or switch to Offline mode.", 0);
                        return;
                    }
                    accountToUse = signedInAccount;
                } else {
                    offlineUsername = offlineUsernameField.getText().trim();
                    if (offlineUsername.isBlank()) {
                        offlineUsername = "Player";
                    }
                    savedOfflineUsername = offlineUsername;
                    saveSettings();
                }
                setStatus("Launching " + selected.id() + "...", 0.9);
                Process process = launchService.launch(
                        selected.id(),
                        meta,
                        accountToUse,
                        offlineUsername,
                        javaPathField.getText().trim(),
                        ramField.getText().trim()
                );
                currentGameProcess = process;
                Platform.runLater(() -> setGameRunning(true));
                process.onExit().thenRun(() -> {
                    if (currentGameProcess == process) {
                        currentGameProcess = null;
                        setStatus("Minecraft closed", 0);
                        Platform.runLater(() -> setGameRunning(false));
                    }
                });
                streamGameLog(process);
                setStatus("Minecraft launched", 0);
            } catch (Exception ex) {
                fail(ex);
                Platform.runLater(() -> setGameRunning(false));
            }
        });
    }

    private void setStatus(String text, double value) {
        Platform.runLater(() -> {
            status.setText(text);
            appendLog("[Limecraft] " + text);
            if (value >= 0 && value <= 1) {
                progress.setProgress(value);
            }
        });
    }

    private void fail(Exception ex) {
        Platform.runLater(() -> {
            status.setText("Error: " + ex.getMessage());
            appendLog("[Limecraft] Error: " + ex.getMessage());
            progress.setProgress(0);
            Alert alert = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK);
            alert.setHeaderText("Limecraft error");
            alert.showAndWait();
        });
    }

    private void streamGameLog(Process process) {
        io.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
                int code = process.waitFor();
                appendLog("[Limecraft] Game process exited with code " + code);
            } catch (Exception ex) {
                appendLog("[Limecraft] Failed to read game log: " + ex.getMessage());
            }
        });
    }

    private void appendLog(String line) {
        Platform.runLater(() -> {
            logOutput.appendText(line + System.lineSeparator());
            logOutput.positionCaret(logOutput.getLength());
        });
    }

    private boolean isGameRunning() {
        return currentGameProcess != null && currentGameProcess.isAlive();
    }

    private void killRunningGame() {
        Process process = currentGameProcess;
        if (process == null) {
            setGameRunning(false);
            return;
        }
        appendLog("[Limecraft] Force-killing game process...");
        currentGameProcess = null;
        process.destroyForcibly();
        setGameRunning(false);
        setStatus("Minecraft process killed", 0);
    }

    private void setGameRunning(boolean running) {
        for (Control control : controlsToDisableOnRun) {
            control.setDisable(running);
        }
        launchButton.setText(running ? "Kill" : "Launch");
        launchButton.getStyleClass().remove("launch-button-kill");
        if (running) {
            launchButton.getStyleClass().add("launch-button-kill");
        }
    }

    private void applyVersionFilter() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean includeExperimental = experimentToggle.isSelected();
        VersionEntry currentSelection = versionsList.getSelectionModel().getSelectedItem();
        String preferredId = currentSelection != null ? currentSelection.id() : lastSelectedVersionId;

        List<VersionEntry> filtered = new ArrayList<>();
        for (VersionEntry v : allVersions) {
            boolean isExperimental = "experiment".equalsIgnoreCase(v.type()) || "pending".equalsIgnoreCase(v.type());
            if (!includeExperimental && isExperimental) {
                continue;
            }
            if (query.isEmpty()) {
                filtered.add(v);
                continue;
            }
            String haystack = (v.id() + " " + v.displayType() + " " + v.releaseTime()).toLowerCase(Locale.ROOT);
            if (haystack.contains(query)) {
                filtered.add(v);
            }
        }

        versionsList.setItems(FXCollections.observableArrayList(filtered));
        if (!filtered.isEmpty()) {
            int preferredIdx = -1;
            if (preferredId != null && !preferredId.isBlank()) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (preferredId.equals(filtered.get(i).id())) {
                        preferredIdx = i;
                        break;
                    }
                }
            }
            versionsList.getSelectionModel().select(preferredIdx >= 0 ? preferredIdx : 0);
        } else {
            selectedVersionLabel.setText("Selected: none");
        }
    }

    private void loadSettings() {
        try {
            Path file = gameDir.resolve(SETTINGS_FILE);
            if (!Files.exists(file)) {
                return;
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
            String stored = props.getProperty(KEY_LAST_VERSION);
            if (stored != null && !stored.isBlank()) {
                lastSelectedVersionId = stored.trim();
            }
            String savedName = props.getProperty(KEY_OFFLINE_USERNAME);
            if (savedName != null && !savedName.isBlank()) {
                savedOfflineUsername = savedName.trim();
            }
            String savedMode = props.getProperty(KEY_ACCOUNT_MODE);
            if (MODE_MICROSOFT.equals(savedMode) || MODE_OFFLINE.equals(savedMode)) {
                savedAccountMode = savedMode;
            }
        } catch (Exception ex) {
            System.err.println("[Limecraft] Failed to load settings: " + ex.getMessage());
        }
    }

    private void saveSettings() {
        try {
            Files.createDirectories(gameDir);
            Path file = gameDir.resolve(SETTINGS_FILE);
            Properties props = new Properties();
            if (accountModeBox != null && accountModeBox.getValue() != null) {
                savedAccountMode = accountModeBox.getValue();
            }
            if (lastSelectedVersionId != null && !lastSelectedVersionId.isBlank()) {
                props.setProperty(KEY_LAST_VERSION, lastSelectedVersionId);
            }
            if (savedOfflineUsername != null && !savedOfflineUsername.isBlank()) {
                props.setProperty(KEY_OFFLINE_USERNAME, savedOfflineUsername);
            }
            if (savedAccountMode != null && !savedAccountMode.isBlank()) {
                props.setProperty(KEY_ACCOUNT_MODE, savedAccountMode);
            }
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "Limecraft launcher settings");
            }
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to save settings: " + ex.getMessage());
        }
    }

    @Override
    public void stop() {
        saveSettings();
        io.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

