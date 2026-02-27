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
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LimecraftApp extends Application {
    private static final String MODE_MICROSOFT = "Microsoft";
    private static final String MODE_OFFLINE = "Offline";
    private static final String SETTINGS_FILE = "launcher.properties";
    private static final String KEY_LAST_VERSION = "last_selected_version";
    private static final String KEY_OFFLINE_USERNAME = "offline_username";
    private static final String KEY_ACCOUNT_MODE = "account_mode";
    private static final String KEY_RECENT_VERSIONS = "recent_versions";

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
    private Button addVersionButton;
    private Button launchButton;
    private Label selectedVersionLabel;
    private ComboBox<String> recentBox;
    private Process currentGameProcess;
    private List<Control> controlsToDisableOnRun = List.of();
    private List<VersionEntry> allVersions = List.of();
    private String lastSelectedVersionId;
    private String savedOfflineUsername = "Player";
    private String savedAccountMode = MODE_MICROSOFT;
    private final List<String> recentVersions = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        loadSettings();

        versionsList = new ListView<>();
        versionsList.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        versionsList.setCellFactory(list -> new ListCell<>() {
            private final Label text = new Label();
            private final Region spacer = new Region();
            private final Region dot = new Region();
            private final HBox row = new HBox(8, text, spacer, dot);
            private final MenuItem deleteItem = new MenuItem("Delete Version");
            private final MenuItem editItem = new MenuItem("Edit Version");
            private final ContextMenu customMenu = new ContextMenu(deleteItem, editItem);
            private final MenuItem duplicateItem = new MenuItem("Duplicate Version");
            private final ContextMenu builtinMenu = new ContextMenu(duplicateItem);

            {
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
                dot.getStyleClass().add("installed-dot");

                deleteItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        deleteCustomVersion(selected);
                    }
                });
                editItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openAddVersionDialog(null, selected);
                    }
                });
                duplicateItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openAddVersionDialog(selected, null);
                    }
                });
            }

            @Override
            protected void updateItem(VersionEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                text.setText(item.toString());
                boolean installed = isVersionInstalled(item);
                if (installed) {
                    dot.getStyleClass().remove("installed-dot-off");
                } else if (!dot.getStyleClass().contains("installed-dot-off")) {
                    dot.getStyleClass().add("installed-dot-off");
                }
                setText(null);
                setGraphic(row);
                setContextMenu(isCustomVersion(item) ? customMenu : builtinMenu);
            }
        });        status = new Label("Ready");
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

        addVersionButton = new Button("Add Version");
        addVersionButton.setOnAction(e -> openAddVersionDialog(null, null));

        launchButton = new Button("Launch");
        launchButton.getStyleClass().add("launch-button");
        launchButton.setOnAction(e -> launchSelected());

        selectedVersionLabel = new Label("Selected: none");
        selectedVersionLabel.getStyleClass().add("selected-version");

        recentBox = new ComboBox<>(FXCollections.observableArrayList(recentVersions));
        recentBox.setPromptText("Recent");
        recentBox.setMaxWidth(Double.MAX_VALUE);
        recentBox.setOnAction(e -> {
            String id = recentBox.getValue();
            if (id != null && !id.isBlank()) {
                selectVersionById(id);
            }
        });

        VBox actionButtons = new VBox(8, launchButton, selectedVersionLabel, labeledNode("Recent", recentBox));
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

        Label versionsLabel = new Label("Minecraft Versions");
        VBox right = new VBox(8, addVersionButton, versionsLabel, versionsList);
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
                addVersionButton,
                recentBox,
                ramField,
                versionsList
        );

        VBox root = new VBox(12, rootRow, progress, logOutput);
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

    private void openAddVersionDialog() {
        openAddVersionDialog(null, null);
    }

    private void openAddVersionDialog(VersionEntry duplicateBase, VersionEntry editingVersion) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(editingVersion != null ? "Edit Version" : "Add Version");

        TextField nameField = new TextField();
        nameField.setPromptText("my-custom-version");

        ComboBox<VersionEntry> baseBox = new ComboBox<>(FXCollections.observableArrayList(allVersions));
        baseBox.setPromptText("Optional base version");
        baseBox.setMaxWidth(Double.MAX_VALUE);

        if (duplicateBase != null) {
            nameField.setText(duplicateBase.id() + "-copy");
            baseBox.setValue(duplicateBase);
        }
        if (editingVersion != null) {
            nameField.setText(editingVersion.id());
            VersionEntry fallbackBase = findVersionById(editingVersion.id());
            if (fallbackBase != null && !isCustomVersion(fallbackBase)) {
                baseBox.setValue(fallbackBase);
            }
        }

        TextField manifestField = new TextField();
        manifestField.setPromptText("Optional manifest .json path");
        Button browseManifest = new Button("Browse");
        browseManifest.setOnAction(e -> {
            Path p = chooseFile("Select Manifest", "JSON Files", "*.json");
            if (p != null) {
                manifestField.setText(p.toString());
            }
        });

        TextField jarField = new TextField();
        jarField.setPromptText("Optional client jar path");
        Button browseJar = new Button("Browse");
        browseJar.setOnAction(e -> {
            Path p = chooseFile("Select Client Jar", "Jar Files", "*.jar");
            if (p != null) {
                jarField.setText(p.toString());
            }
        });
        CheckBox replacementMode = new CheckBox("Jar replacement mode (use base manifest)");
        replacementMode.setSelected(true);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.add(new Label("Name"), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label("Base Version"), 0, 1);
        form.add(baseBox, 1, 1);
        form.add(new Label("Manifest"), 0, 2);
        form.add(new HBox(8, manifestField, browseManifest), 1, 2);
        form.add(new Label("Jar"), 0, 3);
        form.add(new HBox(8, jarField, browseJar), 1, 3);
        form.add(new Label("Mode"), 0, 4);
        form.add(replacementMode, 1, 4);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(110);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);

        Label note = new Label("Replacement mode uses base manifest and optional replacement jar.");
        note.getStyleClass().add("selected-version");

        Button create = new Button(editingVersion != null ? "Save" : "Create");
        create.getStyleClass().add("launch-button");
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());

        create.setOnAction(e -> {
            String customId = nameField.getText() == null ? "" : nameField.getText().trim();
            if (customId.isBlank()) {
                fail(new IllegalArgumentException("Name is required."));
                return;
            }
            if (customId.matches(".*[\\\\/:*?\"<>|].*")) {
                fail(new IllegalArgumentException("Name contains invalid path characters."));
                return;
            }

            VersionEntry base = baseBox.getValue();
            Path manifest = toOptionalPath(manifestField.getText());
            Path jar = toOptionalPath(jarField.getText());
            boolean replaceJarMode = replacementMode.isSelected();
            if (replaceJarMode && base == null) {
                fail(new IllegalArgumentException("Select a base version for jar replacement mode."));
                return;
            }
            dialog.close();

            io.submit(() -> {
                try {
                    createCustomVersion(customId, base, manifest, jar, replaceJarMode);
                    VersionEntry custom = new VersionEntry(customId, "custom", "", Instant.now().toString());
                    Platform.runLater(() -> {
                        List<VersionEntry> updated = new ArrayList<>(allVersions);
                        updated.removeIf(v -> v.id().equalsIgnoreCase(customId));
                        updated.add(0, custom);
                        allVersions = updated;
                        lastSelectedVersionId = customId;
                        saveSettings();
                        applyVersionFilter();
                        versionsList.refresh();
                        setStatus((editingVersion != null ? "Updated" : "Added") + " custom version " + customId, 0);
                    });
                } catch (Exception ex) {
                    fail(ex);
                }
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, create, cancel);

        VBox root = new VBox(10, form, note, buttons);
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 760, 250);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private Path chooseFile(String title, String extName, String extPattern) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extName, extPattern));
        File selected = chooser.showOpenDialog(null);
        return selected == null ? null : selected.toPath();
    }

    private Path toOptionalPath(String text) {
        if (text == null || text.trim().isBlank()) {
            return null;
        }
        return Path.of(text.trim());
    }

    private void createCustomVersion(String customId, VersionEntry base, Path manifestOverride, Path jarOverride, boolean replaceJarMode) throws Exception {
        Path targetDir = gameDir.resolve("versions").resolve(customId);
        Files.createDirectories(targetDir);

        Path manifestSource;
        Path jarSource;

        if (replaceJarMode) {
            if (base == null) {
                throw new IllegalStateException("Base version is required for jar replacement mode.");
            }
            ensureBaseInstalled(base);
            manifestSource = gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".json");
            jarSource = jarOverride != null
                    ? jarOverride
                    : gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".jar");
        } else {
            manifestSource = manifestOverride;
            jarSource = jarOverride;
            if (base != null) {
                ensureBaseInstalled(base);
                if (manifestSource == null) {
                    manifestSource = gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".json");
                }
                if (jarSource == null) {
                    jarSource = gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".jar");
                }
            }
        }

        if (manifestSource == null || !Files.exists(manifestSource)) {
            throw new IllegalStateException("Manifest not found. Pick one or choose a base version.");
        }
        if (jarSource == null || !Files.exists(jarSource)) {
            throw new IllegalStateException("Jar not found. Pick one or choose a base version.");
        }

        Path targetManifest = targetDir.resolve(customId + ".json");
        Path targetJar = targetDir.resolve(customId + ".jar");

        Files.copy(manifestSource, targetManifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(jarSource, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        JsonObject meta = JsonParser.parseString(Files.readString(targetManifest)).getAsJsonObject();
        meta.addProperty("id", customId);
        meta.addProperty("type", "custom");
        Files.writeString(targetManifest, meta.toString());
    }

    private boolean isCustomVersion(VersionEntry version) {
        return version != null && "custom".equalsIgnoreCase(version.type());
    }

    private void deleteCustomVersion(VersionEntry version) {
        if (!isCustomVersion(version)) {
            setStatus("Only custom versions can be deleted from the launcher list.", 0);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete custom version '" + version.id() + "' and its files?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Delete Version");
        confirm.showAndWait();
        if (confirm.getResult() != ButtonType.OK) {
            return;
        }

        io.submit(() -> {
            try {
                Path versionDir = gameDir.resolve("versions").resolve(version.id());
                Path instanceDir = gameDir.resolve("instances").resolve(version.id().replaceAll("[\\/:*?\"<>|]", "_"));
                deleteDirectoryIfExists(versionDir);
                deleteDirectoryIfExists(instanceDir);
                Platform.runLater(() -> {
                    List<VersionEntry> updated = new ArrayList<>(allVersions);
                    updated.removeIf(v -> v.id().equalsIgnoreCase(version.id()));
                    allVersions = updated;
                    if (version.id().equalsIgnoreCase(lastSelectedVersionId)) {
                        lastSelectedVersionId = null;
                    }
                    saveSettings();
                    applyVersionFilter();
                    versionsList.refresh();
                    setStatus("Deleted custom version " + version.id(), 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void deleteDirectoryIfExists(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }
    private void ensureBaseInstalled(VersionEntry base) throws Exception {
        Path baseDir = gameDir.resolve("versions").resolve(base.id());
        boolean hasJson = Files.exists(baseDir.resolve(base.id() + ".json"));
        boolean hasJar = Files.exists(baseDir.resolve(base.id() + ".jar"));
        if (hasJson && hasJar) {
            return;
        }
        if (base.url() == null || base.url().isBlank()) {
            throw new IllegalStateException("Base version " + base.id() + " is not installed.");
        }
        setStatus("Installing base version " + base.id() + " for custom copy...", 0.1);
        installService.installVersion(base, this::setStatus);
    }
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
                List<VersionEntry> official = installService.listVersions();
                List<VersionEntry> custom = loadCustomVersions();

                List<VersionEntry> combined = new ArrayList<>(custom);
                for (VersionEntry version : official) {
                    boolean duplicate = combined.stream().anyMatch(v -> v.id().equalsIgnoreCase(version.id()));
                    if (!duplicate) {
                        combined.add(version);
                    }
                }

                Platform.runLater(() -> {
                    allVersions = combined;
                    versionsList.refresh();
                    applyVersionFilter();
                    setStatus("Loaded " + official.size() + " official + " + custom.size() + " custom versions", 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private List<VersionEntry> loadCustomVersions() {
        List<VersionEntry> custom = new ArrayList<>();
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return custom;
        }

        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String id = dir.getFileName().toString();
                Path json = dir.resolve(id + ".json");
                Path jar = dir.resolve(id + ".jar");
                if (!Files.exists(json) || !Files.exists(jar)) {
                    return;
                }
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    String type = meta.has("type") ? meta.get("type").getAsString() : "";
                    if (!"custom".equalsIgnoreCase(type)) {
                        return;
                    }
                    String releaseTime = meta.has("releaseTime")
                            ? meta.get("releaseTime").getAsString()
                            : Instant.now().toString();
                    custom.add(new VersionEntry(id, "custom", "", releaseTime));
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to load custom versions: " + ex.getMessage());
        }

        custom.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        return custom;
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
                    Platform.runLater(() -> versionsList.refresh());
                    Platform.runLater(() -> versionsList.refresh());
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
                Platform.runLater(() -> {
                    setGameRunning(true);
                    recordRecentLaunch(selected.id());
                });
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

    private boolean isVersionInstalled(VersionEntry version) {
        Path versionDir = gameDir.resolve("versions").resolve(version.id());
        return Files.exists(versionDir.resolve(version.id() + ".json"))
                && Files.exists(versionDir.resolve(version.id() + ".jar"));
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

    private VersionEntry findVersionById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (VersionEntry v : allVersions) {
            if (id.equalsIgnoreCase(v.id())) {
                return v;
            }
        }
        return null;
    }

    private void selectVersionById(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        for (VersionEntry v : versionsList.getItems()) {
            if (id.equalsIgnoreCase(v.id())) {
                versionsList.getSelectionModel().select(v);
                versionsList.scrollTo(v);
                return;
            }
        }
    }

    private void recordRecentLaunch(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return;
        }
        recentVersions.removeIf(v -> v.equalsIgnoreCase(versionId));
        recentVersions.add(0, versionId);
        while (recentVersions.size() > 5) {
            recentVersions.remove(recentVersions.size() - 1);
        }
        if (recentBox != null) {
            recentBox.setItems(FXCollections.observableArrayList(recentVersions));
            recentBox.getSelectionModel().clearSelection();
        }
        saveSettings();
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
            String recentRaw = props.getProperty(KEY_RECENT_VERSIONS);
            if (recentRaw != null && !recentRaw.isBlank()) {
                for (String value : recentRaw.split("\\|")) {
                    String id = value.trim();
                    if (!id.isBlank()) {
                        recentVersions.add(id);
                        if (recentVersions.size() >= 5) {
                            break;
                        }
                    }
                }
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
            if (!recentVersions.isEmpty()) {
                props.setProperty(KEY_RECENT_VERSIONS, String.join("|", recentVersions));
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








































