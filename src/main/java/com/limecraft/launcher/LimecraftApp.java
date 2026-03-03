package com.limecraft.launcher;

import com.google.gson.JsonArray;
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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
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
    private static final String KEY_BRANDING_SUFFIX = "include_limecraft_suffix";
    private static final String MICROSOFT_CLIENT_ID = "ba5cdd7c-bc36-4702-9613-35f14f83e52c";

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
    private ComboBox<String> accountModeBox;
    private TextField offlineUsernameField;
    private TextField searchField;
    private CheckBox experimentToggle;
    private CheckBox limecraftSuffixToggle;
    private Button signInButton;
    private Button detectJavaButton;
    private Button addVersionButton;
    private Button installFabricButton;
    private Button launchButton;
    private Label selectedVersionLabel;
    private ComboBox<String> recentBox;
    private ListView<VersionEntry> serverVersionsList;
    private TextField serverSearchField;
    private CheckBox serverExperimentalToggle;
    private TextField serverJavaPathField;
    private TextField serverRamField;
    private TextField serverPortField;
    private TextField serverMotdField;
    private TextField serverMaxPlayersField;
    private CheckBox serverOnlineModeToggle;
    private CheckBox serverPvpToggle;
    private CheckBox serverCommandBlocksToggle;
    private CheckBox serverNoguiToggle;
    private Button serverLaunchButton;
    private Label serverSelectedVersionLabel;
    private ProgressBar serverProgress;
    private TextArea serverLogOutput;
    private TextField serverCommandField;
    private Button serverSendButton;
    private BufferedWriter serverCommandWriter;
    private Process currentServerProcess;
    private List<Control> serverControlsToDisableOnRun = List.of();    private Process currentGameProcess;
    private List<Control> controlsToDisableOnRun = List.of();
    private List<VersionEntry> allVersions = List.of();
    private String lastSelectedVersionId;
    private String savedOfflineUsername = "Player";
    private String savedAccountMode = MODE_MICROSOFT;
    private boolean includeLimecraftSuffix = true;
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
            private final MenuItem fabricDeleteItem = new MenuItem("delete version");
            private final MenuItem openModsFolderItem = new MenuItem("open mods folder");
            private final ContextMenu fabricMenu = new ContextMenu(fabricDeleteItem, openModsFolderItem);
            private final MenuItem duplicateItem = new MenuItem("Duplicate Version");
            private final MenuItem showChangelogItem = new MenuItem("Show Changelog");
            private final ContextMenu builtinMenu = new ContextMenu(duplicateItem, showChangelogItem);

            {
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
                dot.getStyleClass().add("installed-dot");

                deleteItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        deleteManagedVersion(selected);
                    }
                });
                editItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openAddVersionDialog(null, selected);
                    }
                });
                fabricDeleteItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        deleteManagedVersion(selected);
                    }
                });
                openModsFolderItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openFabricModsFolder(selected);
                    }
                });
                duplicateItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openAddVersionDialog(selected, null);
                    }
                });
                showChangelogItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        showVersionChangelog(selected);
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
                if (isFabricVersion(item)) {
                    openModsFolderItem.setDisable(!hasLaunchedVersion(item));
                    setContextMenu(fabricMenu);
                } else if (isCustomVersion(item)) {
                    setContextMenu(customMenu);
                } else {
                    setContextMenu(builtinMenu);
                }
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

        limecraftSuffixToggle = new CheckBox("Add Limecraft version suffix");
        limecraftSuffixToggle.setSelected(includeLimecraftSuffix);
        limecraftSuffixToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            includeLimecraftSuffix = Boolean.TRUE.equals(newVal);
            saveSettings();
        });

        signInButton = new Button("Sign In (Microsoft)");
        signInButton.setOnAction(e -> signIn());

        addVersionButton = new Button("Add Version");
        addVersionButton.setOnAction(e -> openAddVersionDialog(null, null));

        installFabricButton = new Button("Install Fabric");
        installFabricButton.setOnAction(e -> openFabricInstallDialog());

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

        VBox actionButtons = new VBox(8, launchButton, selectedVersionLabel, labeledNode("Recent", recentBox), limecraftSuffixToggle);
        launchButton.setMaxWidth(Double.MAX_VALUE);
        signInButton.setMaxWidth(Double.MAX_VALUE);

        VBox microsoftAuthBox = new VBox(8,
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
        VBox right = new VBox(8, addVersionButton, installFabricButton, versionsLabel, versionsList);
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
                signInButton,
                offlineUsernameField,
                searchField,
                experimentToggle,
                javaPathField,
                detectJavaButton,
                addVersionButton,
                installFabricButton,
                recentBox,
                limecraftSuffixToggle,
                ramField,
                versionsList
        );

                VBox clientRoot = new VBox(12, rootRow, progress, logOutput);
        clientRoot.setPadding(new Insets(16));
        VBox.setVgrow(rootRow, Priority.ALWAYS);
        VBox.setVgrow(logOutput, Priority.SOMETIMES);

        Tab clientTab = new Tab("Client", clientRoot);
        clientTab.setClosable(false);
        Tab serverTab = new Tab("Server", createServerTab());
        serverTab.setClosable(false);

        TabPane tabPane = new TabPane(clientTab, serverTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Scene scene = new Scene(tabPane, 980, 640);
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

    private VBox labeledNode(String label, Node node) {
        Label l = new Label(label);
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(4, l, node);
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

    private VBox createServerTab() {
        serverVersionsList = new ListView<>();
        serverVersionsList.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        serverVersionsList.setCellFactory(list -> new ListCell<>() {
            private final Label text = new Label();
            private final Region spacer = new Region();
            private final Region dot = new Region();
            private final HBox row = new HBox(8, text, spacer, dot);

            {
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
                dot.getStyleClass().add("installed-dot");
            }

            @Override
            protected void updateItem(VersionEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                text.setText(item.toString());
                boolean installed = isServerInstalled(item);
                if (installed) {
                    dot.getStyleClass().remove("installed-dot-off");
                } else if (!dot.getStyleClass().contains("installed-dot-off")) {
                    dot.getStyleClass().add("installed-dot-off");
                }
                setText(null);
                setGraphic(row);
            }
        });

        serverSearchField = new TextField();
        serverSearchField.setPromptText("Search a version...");
        serverSearchField.getStyleClass().add("search-field");
        serverSearchField.textProperty().addListener((obs, oldVal, newVal) -> applyServerVersionFilter());

        serverExperimentalToggle = new CheckBox("Show Experimental");
        serverExperimentalToggle.setSelected(true);
        serverExperimentalToggle.selectedProperty().addListener((obs, oldVal, newVal) -> applyServerVersionFilter());

        serverJavaPathField = new TextField("java");
        Button useClientJavaButton = new Button("Use Client Java");
        useClientJavaButton.setOnAction(e -> serverJavaPathField.setText(javaPathField.getText().trim()));
        HBox serverJavaRow = new HBox(8, serverJavaPathField, useClientJavaButton);
        HBox.setHgrow(serverJavaPathField, Priority.ALWAYS);

        serverRamField = new TextField("2G");
        serverPortField = new TextField("25565");
        serverMotdField = new TextField("A Limecraft Server");
        serverMaxPlayersField = new TextField("20");
        serverOnlineModeToggle = new CheckBox("Online Mode");
        serverOnlineModeToggle.setSelected(true);
        serverPvpToggle = new CheckBox("PVP");
        serverPvpToggle.setSelected(true);
        serverCommandBlocksToggle = new CheckBox("Enable Command Blocks");
        serverCommandBlocksToggle.setSelected(false);
        serverNoguiToggle = new CheckBox("No GUI");
        serverNoguiToggle.setSelected(true);

        serverLaunchButton = new Button("Launch");
        serverLaunchButton.getStyleClass().add("launch-button");
        serverLaunchButton.setMaxWidth(Double.MAX_VALUE);
        serverLaunchButton.setOnAction(e -> launchSelectedServer());
        serverSelectedVersionLabel = new Label("Selected: none");
        serverSelectedVersionLabel.getStyleClass().add("selected-version");

        VBox left = new VBox(12,
                title("Limecraft"),
                labeledNode("Search Versions", serverSearchField),
                serverExperimentalToggle,
                labeledNode("Java Path", serverJavaRow),
                labeledNode("Max Memory (-Xmx)", serverRamField),
                labeledNode("Server Port", serverPortField),
                labeledNode("MOTD", serverMotdField),
                labeledNode("Max Players", serverMaxPlayersField),
                serverOnlineModeToggle,
                serverPvpToggle,
                serverCommandBlocksToggle,
                serverNoguiToggle,
                serverLaunchButton,
                serverSelectedVersionLabel
        );
        left.setPrefWidth(340);
        left.setMinWidth(320);
        left.setMaxWidth(420);

        Label versionsLabel = new Label("Minecraft Versions");
        VBox right = new VBox(8, versionsLabel, serverVersionsList);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(serverVersionsList, Priority.ALWAYS);
        right.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(18, left, right);

        serverProgress = new ProgressBar(0);
        serverProgress.setMaxWidth(Double.MAX_VALUE);
        serverLogOutput = new TextArea();
        serverLogOutput.setEditable(false);
        serverLogOutput.setWrapText(true);
        serverLogOutput.setPromptText("Server log output will appear here...");
        serverLogOutput.getStyleClass().add("console-log");

        serverCommandField = new TextField();
        serverCommandField.setPromptText("Type server command...");
        serverCommandField.setDisable(true);
        serverCommandField.setOnAction(e -> sendServerCommand());

        serverSendButton = new Button("Send");
        serverSendButton.setDisable(true);
        serverSendButton.setOnAction(e -> sendServerCommand());

        HBox serverCommandRow = new HBox(8, serverCommandField, serverSendButton);
        HBox.setHgrow(serverCommandField, Priority.ALWAYS);

        serverVersionsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            serverSelectedVersionLabel.setText(newVal == null ? "Selected: none" : "Selected: " + newVal.id());
        });

        serverControlsToDisableOnRun = List.of(
                serverSearchField,
                serverExperimentalToggle,
                serverJavaPathField,
                useClientJavaButton,
                serverRamField,
                serverPortField,
                serverMotdField,
                serverMaxPlayersField,
                serverOnlineModeToggle,
                serverPvpToggle,
                serverCommandBlocksToggle,
                serverNoguiToggle,
                serverVersionsList
        );

        VBox root = new VBox(12, row, serverProgress, serverLogOutput, serverCommandRow);
        root.setPadding(new Insets(16));
        VBox.setVgrow(row, Priority.ALWAYS);
        VBox.setVgrow(serverLogOutput, Priority.SOMETIMES);
        return root;
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

    private void openFabricInstallDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Install Fabric");

        List<VersionEntry> official = new ArrayList<>();
        for (VersionEntry v : allVersions) {
            if (v.url() != null && !v.url().isBlank()) {
                official.add(v);
            }
        }

        ComboBox<VersionEntry> baseBox = new ComboBox<>(FXCollections.observableArrayList(official));
        baseBox.setPromptText("Select Minecraft version");
        baseBox.setMaxWidth(Double.MAX_VALUE);
        VersionEntry selected = versionsList.getSelectionModel().getSelectedItem();
        if (selected != null && !isCustomVersion(selected)) {
            baseBox.setValue(selected);
        } else if (!official.isEmpty()) {
            baseBox.getSelectionModel().select(0);
        }

        TextField nameField = new TextField();
        nameField.setPromptText("Optional custom name (default: fabric-<mcVersion>)");

        Label note = new Label("Installs latest stable Fabric loader for the selected Minecraft version.");
        note.getStyleClass().add("selected-version");

        Button install = new Button("Install");
        install.getStyleClass().add("launch-button");
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());

        install.setOnAction(e -> {
            VersionEntry base = baseBox.getValue();
            if (base == null) {
                fail(new IllegalArgumentException("Select a Minecraft version first."));
                return;
            }
            String requestedName = nameField.getText() == null ? "" : nameField.getText().trim();
            if (!requestedName.isBlank() && requestedName.matches(".*[\\\\/:*?\"<>|].*")) {
                fail(new IllegalArgumentException("Name contains invalid path characters."));
                return;
            }
            dialog.close();
            io.submit(() -> {
                try {
                    installFabricVersion(base, requestedName);
                } catch (Exception ex) {
                    fail(ex);
                }
            });
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.add(new Label("Minecraft Version"), 0, 0);
        form.add(baseBox, 1, 0);
        form.add(new Label("Custom Name"), 0, 1);
        form.add(nameField, 1, 1);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(120);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, install, cancel);

        VBox root = new VBox(10, form, note, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 700, 180);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void installFabricVersion(VersionEntry base, String requestedName) throws Exception {
        if (base.url() == null || base.url().isBlank()) {
            throw new IllegalStateException("Selected base version does not have official metadata URL.");
        }

        ensureBaseInstalled(base);

        setStatus("Resolving Fabric loader for " + base.id() + "...", 0.2);
        String loaderVersion = fetchLatestFabricLoaderVersion(base.id());
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IllegalStateException("No Fabric loader available for " + base.id());
        }

        String mcSegment = URLEncoder.encode(base.id(), StandardCharsets.UTF_8).replace("+", "%20");
        String loaderSegment = URLEncoder.encode(loaderVersion, StandardCharsets.UTF_8).replace("+", "%20");
        String profileUrl = "https://meta.fabricmc.net/v2/versions/loader/" + mcSegment + "/" + loaderSegment + "/profile/json";

        setStatus("Downloading Fabric profile...", 0.35);
        JsonObject profile = http.getJson(profileUrl);

        String profileId = profile.has("id") ? profile.get("id").getAsString() : ("fabric-loader-" + loaderVersion + "-" + base.id());
        String customId = (requestedName == null || requestedName.isBlank()) ? profileId : requestedName;

        Path targetDir = gameDir.resolve("versions").resolve(customId);
        Files.createDirectories(targetDir);

        profile.addProperty("id", customId);
        profile.addProperty("type", "fabric");
        profile.addProperty("releaseTime", Instant.now().toString());

        Path targetManifest = targetDir.resolve(customId + ".json");
        Files.writeString(targetManifest, profile.toString(), StandardCharsets.UTF_8);

        setStatus("Downloading Fabric dependencies...", 0.5);
        installService.installMetadataDependencies(profile, this::setStatus);
        ensureInheritedDependenciesInstalled(profile);

        VersionEntry custom = new VersionEntry(customId, "fabric", "", Instant.now().toString());
        Platform.runLater(() -> {
            List<VersionEntry> updated = new ArrayList<>(allVersions);
            updated.removeIf(v -> v.id().equalsIgnoreCase(customId));
            updated.add(0, custom);
            allVersions = updated;
            lastSelectedVersionId = customId;
            saveSettings();
            applyVersionFilter();
            versionsList.refresh();
            setStatus("Installed Fabric version " + customId, 0);
        });
    }

    private String fetchLatestFabricLoaderVersion(String minecraftVersion) throws Exception {
        String segment = URLEncoder.encode(minecraftVersion, StandardCharsets.UTF_8).replace("+", "%20");
        JsonArray loaders = getJsonArray("https://meta.fabricmc.net/v2/versions/loader/" + segment);
        if (loaders.isEmpty()) {
            return null;
        }

        String first = null;
        for (int i = 0; i < loaders.size(); i++) {
            JsonObject loader = loaders.get(i).getAsJsonObject();
            if (first == null && loader.has("loader")) {
                first = loader.getAsJsonObject("loader").get("version").getAsString();
            }
            if (loader.has("loader")
                    && loader.getAsJsonObject("loader").has("stable")
                    && loader.getAsJsonObject("loader").get("stable").getAsBoolean()) {
                return loader.getAsJsonObject("loader").get("version").getAsString();
            }
        }
        return first;
    }

    private JsonArray getJsonArray(String url) throws Exception {
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();
        try (okhttp3.Response response = http.raw().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code() + " for " + url);
            }
            return JsonParser.parseString(response.body().string()).getAsJsonArray();
        }
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

    private boolean isFabricVersion(VersionEntry version) {
        return version != null && "fabric".equalsIgnoreCase(version.type());
    }

    private boolean isManagedVersion(VersionEntry version) {
        return isCustomVersion(version) || isFabricVersion(version);
    }

    private void deleteManagedVersion(VersionEntry version) {
        if (!isManagedVersion(version)) {
            setStatus("Only custom/fabric versions can be deleted from the launcher list.", 0);
            return;
        }
        String kind = isFabricVersion(version) ? "fabric" : "custom";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + kind + " version '" + version.id() + "' and its files?",
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
                    setStatus("Deleted " + kind + " version " + version.id(), 0);
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

    private boolean hasLaunchedVersion(VersionEntry version) {
        if (version == null) {
            return false;
        }
        Path instanceDir = gameDir.resolve("instances").resolve(safeFolderName(version.id()));
        return Files.isDirectory(instanceDir);
    }

    private void openFabricModsFolder(VersionEntry version) {
        if (!isFabricVersion(version)) {
            setStatus("Mods folder is only available for Fabric versions.", 0);
            return;
        }
        Path instanceDir = gameDir.resolve("instances").resolve(safeFolderName(version.id()));
        if (!Files.isDirectory(instanceDir)) {
            setStatus("Launch this Fabric version once before opening its mods folder.", 0);
            return;
        }
        Path modsDir = instanceDir.resolve("mods");
        io.submit(() -> {
            try {
                Files.createDirectories(modsDir);
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("Desktop integration is not supported on this system.");
                }
                Desktop.getDesktop().open(modsDir.toFile());
                setStatus("Opened mods folder for " + version.id(), 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void showVersionChangelog(VersionEntry version) {
        if (version == null || isManagedVersion(version)) {
            setStatus("Changelog is only available for vanilla versions.", 0);
            return;
        }

        String query = "Java Edition " + version.id() + " changelog";
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        String changelogUrl = "https://minecraft.wiki/w/Special:Search?search=" + encoded;

        io.submit(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new IllegalStateException("Desktop browse integration is not supported on this system.");
                }
                Desktop.getDesktop().browse(URI.create(changelogUrl));
                setStatus("Opened changelog for " + version.id(), 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
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
                    applyServerVersionFilter();
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

                if (!Files.exists(json)) {
                    return;
                }
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    String type = meta.has("type") ? meta.get("type").getAsString() : "";
                    boolean fabric = isFabricMetadata(meta);
                    if (!"custom".equalsIgnoreCase(type) && !fabric) {
                        return;
                    }
                    String releaseTime = meta.has("releaseTime")
                            ? meta.get("releaseTime").getAsString()
                            : Instant.now().toString();
                    custom.add(new VersionEntry(id, fabric ? "fabric" : "custom", "", releaseTime));
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to load custom versions: " + ex.getMessage());
        }

        custom.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        return custom;
    }

    private boolean isFabricMetadata(JsonObject meta) {
        if (meta == null) {
            return false;
        }
        if (meta.has("type") && "fabric".equalsIgnoreCase(meta.get("type").getAsString())) {
            return true;
        }
        if (!meta.has("libraries") || !meta.get("libraries").isJsonArray()) {
            return false;
        }
        JsonArray libs = meta.getAsJsonArray("libraries");
        for (int i = 0; i < libs.size(); i++) {
            JsonObject lib = libs.get(i).getAsJsonObject();
            if (!lib.has("name")) {
                continue;
            }
            String name = lib.get("name").getAsString().toLowerCase(Locale.ROOT);
            if (name.startsWith("net.fabricmc:fabric-loader:")) {
                return true;
            }
        }
        return false;
    }

    private void applyServerVersionFilter() {
        if (serverVersionsList == null) {
            return;
        }
        String query = serverSearchField == null || serverSearchField.getText() == null
                ? ""
                : serverSearchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean includeExperimental = serverExperimentalToggle == null || serverExperimentalToggle.isSelected();

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

        serverVersionsList.setItems(FXCollections.observableArrayList(filtered));
        if (!filtered.isEmpty() && serverVersionsList.getSelectionModel().getSelectedItem() == null) {
            serverVersionsList.getSelectionModel().select(0);
        }
    }

    private boolean isServerInstalled(VersionEntry version) {
        Path serverJar = gameDir.resolve("servers").resolve(version.id()).resolve("server.jar");
        return Files.exists(serverJar);
    }

    private void launchSelectedServer() {
        if (isServerRunning()) {
            killRunningServer();
            return;
        }
        VersionEntry selected = serverVersionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            appendServerLog("[Limecraft] Select a server version first");
            return;
        }

        io.submit(() -> {
            try {
                Path serverDir = gameDir.resolve("servers").resolve(selected.id());
                Path serverJar = ensureServerInstalled(selected, serverDir);
                writeServerFiles(serverDir);

                List<String> args = new ArrayList<>();
                String javaPath = serverJavaPathField.getText() == null || serverJavaPathField.getText().trim().isBlank()
                        ? "java"
                        : serverJavaPathField.getText().trim();
                String xmx = serverRamField.getText() == null || serverRamField.getText().trim().isBlank()
                        ? "2G"
                        : serverRamField.getText().trim();
                args.add(javaPath);
                args.add("-Xmx" + xmx);
                args.add("-jar");
                args.add(serverJar.getFileName().toString());
                if (serverNoguiToggle.isSelected()) {
                    args.add("nogui");
                }

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(serverDir.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentServerProcess = process;
                serverCommandWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                Platform.runLater(() -> setServerRunning(true));

                process.onExit().thenRun(() -> {
                    if (currentServerProcess == process) {
                        currentServerProcess = null;
                        serverCommandWriter = null;
                        appendServerLog("[Limecraft] Server closed");
                        Platform.runLater(() -> setServerRunning(false));
                    }
                });

                streamServerLog(process);
                appendServerLog("[Limecraft] Server launched");
            } catch (Exception ex) {
                fail(ex);
                Platform.runLater(() -> setServerRunning(false));
            }
        });
    }

    private Path ensureServerInstalled(VersionEntry version, Path serverDir) throws Exception {
        Files.createDirectories(serverDir);
        Path serverJar = serverDir.resolve("server.jar");
        if (Files.exists(serverJar)) {
            return serverJar;
        }

        appendServerLog("[Limecraft] Downloading server for " + version.id() + "...");
        JsonObject meta = http.getJson(version.url());
        if (!meta.has("downloads") || !meta.getAsJsonObject("downloads").has("server")) {
            throw new IllegalStateException("Version " + version.id() + " has no dedicated server download.");
        }
        String serverUrl = meta.getAsJsonObject("downloads").getAsJsonObject("server").get("url").getAsString();

        okhttp3.Request request = new okhttp3.Request.Builder().url(serverUrl).get().build();
        try (okhttp3.Response response = http.raw().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Failed to download server jar: HTTP " + response.code());
            }
            Files.write(serverJar, response.body().bytes());
        }
        appendServerLog("[Limecraft] Server download complete");
        return serverJar;
    }

    private void writeServerFiles(Path serverDir) throws Exception {
        Files.createDirectories(serverDir);
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true" + System.lineSeparator());

        List<String> lines = new ArrayList<>();
        lines.add("server-port=" + sanitizeInt(serverPortField.getText(), 25565));
        lines.add("motd=" + sanitizeText(serverMotdField.getText(), "A Limecraft Server"));
        lines.add("max-players=" + sanitizeInt(serverMaxPlayersField.getText(), 20));
        lines.add("online-mode=" + serverOnlineModeToggle.isSelected());
        lines.add("pvp=" + serverPvpToggle.isSelected());
        lines.add("enable-command-block=" + serverCommandBlocksToggle.isSelected());
        Files.write(serverDir.resolve("server.properties"), lines, StandardCharsets.UTF_8);
    }

    private String sanitizeText(String text, String fallback) {
        if (text == null || text.trim().isBlank()) {
            return fallback;
        }
        return text.trim();
    }

    private int sanitizeInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void streamServerLog(Process process) {
        io.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendServerLog(line);
                }
            } catch (Exception ex) {
                appendServerLog("[Limecraft] Failed to read server log: " + ex.getMessage());
            }
        });
    }

    private boolean isServerRunning() {
        return currentServerProcess != null && currentServerProcess.isAlive();
    }

    private void killRunningServer() {
        Process process = currentServerProcess;
        if (process == null) {
            setServerRunning(false);
            return;
        }
        appendServerLog("[Limecraft] Force-killing server process...");
        currentServerProcess = null;
        serverCommandWriter = null;
        process.destroyForcibly();
        setServerRunning(false);
    }

    private void setServerRunning(boolean running) {
        for (Control control : serverControlsToDisableOnRun) {
            control.setDisable(running);
        }
        if (serverCommandField != null) {
            serverCommandField.setDisable(!running);
        }
        if (serverSendButton != null) {
            serverSendButton.setDisable(!running);
        }
        serverLaunchButton.setText(running ? "Kill" : "Launch");
        serverLaunchButton.getStyleClass().remove("launch-button-kill");
        if (running) {
            serverLaunchButton.getStyleClass().add("launch-button-kill");
        }
    }

    private void sendServerCommand() {
        String command = serverCommandField == null ? "" : serverCommandField.getText();
        if (command == null || command.trim().isBlank()) {
            return;
        }
        if (!isServerRunning() || serverCommandWriter == null) {
            appendServerLog("[Limecraft] Server is not running.");
            return;
        }
        String trimmed = command.trim();
        io.submit(() -> {
            try {
                synchronized (this) {
                    serverCommandWriter.write(trimmed);
                    serverCommandWriter.newLine();
                    serverCommandWriter.flush();
                }
                appendServerLog("> " + trimmed);
            } catch (Exception ex) {
                appendServerLog("[Limecraft] Failed to send command: " + ex.getMessage());
            }
        });
        serverCommandField.clear();
    }

    private void appendServerLog(String line) {
        Platform.runLater(() -> {
            if (serverLogOutput != null) {
                serverLogOutput.appendText(line + System.lineSeparator());
                serverLogOutput.positionCaret(serverLogOutput.getLength());
            }
            if (serverProgress != null && line.contains("Downloading")) {
                serverProgress.setProgress(0.5);
            }
        });
    }

    private void signIn() {
        if (!MODE_MICROSOFT.equals(accountModeBox.getValue())) {
            setStatus("Switch Account Mode to Microsoft to sign in.", 0);
            return;
        }
        String clientId = MICROSOFT_CLIENT_ID;

        appendLog("[Limecraft] Microsoft auth client id: " + clientId);
        appendLog("[Limecraft] Microsoft auth endpoint: /consumers/oauth2/v2.0/devicecode");
        setStatus("Starting Microsoft device login...", 0.1);
        io.submit(() -> {
            try {
                MicrosoftAuthService auth = new MicrosoftAuthService(http, clientId);
                DeviceCodeInfo code = auth.beginDeviceLogin();

                Platform.runLater(() -> {
                    String otcCode = code.userCode() == null ? "" : code.userCode().trim();
                    String link = (code.verificationUri() == null || code.verificationUri().isBlank()) ? "https://microsoft.com/devicelogin" : code.verificationUri();

                    if (!otcCode.isBlank()) {
                        ClipboardContent clipContent = new ClipboardContent();
                        clipContent.putString(otcCode);
                        Clipboard.getSystemClipboard().setContent(clipContent);
                    }

                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setHeaderText("Microsoft Sign-In");
                    alert.setTitle("Device Code Login");

                    Label instructions = new Label("Open this link, then enter the code to continue sign-in:");
                    Hyperlink signInLink = new Hyperlink(link);
                    signInLink.setOnAction(evt -> getHostServices().showDocument(link));
                    Label codeLabel = new Label("Code: " + otcCode);

                    VBox content = new VBox(8, instructions, signInLink, codeLabel);
                    alert.getDialogPane().setContent(content);
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
                Path versionDir = gameDir.resolve("versions").resolve(selected.id());
                Path versionJson = versionDir.resolve(selected.id() + ".json");
                Path versionJar = versionDir.resolve(selected.id() + ".jar");

                if (!Files.exists(versionJson)) {
                    if (selected.url() == null || selected.url().isBlank()) {
                        throw new IllegalStateException("Version metadata is missing for " + selected.id());
                    }
                    setStatus("Installing " + selected.id() + " before launch...", 0.15);
                    installService.installVersion(selected, this::setStatus);
                    setStatus("Installed " + selected.id(), 0.8);
                }

                JsonObject meta = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
                if (!Files.exists(versionJar) && !meta.has("inheritsFrom")) {
                    if (selected.url() == null || selected.url().isBlank()) {
                        throw new IllegalStateException("Missing client jar for " + selected.id());
                    }
                    setStatus("Installing missing jar for " + selected.id() + "...", 0.25);
                    installService.installVersion(selected, this::setStatus);
                    meta = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
                }
                if (meta.has("inheritsFrom")) {
                    setStatus("Checking inherited dependencies for " + selected.id() + "...", 0.55);
                    installService.installMetadataDependencies(meta, this::setStatus);
                    ensureInheritedDependenciesInstalled(meta);
                }

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
                        ramField.getText().trim(),
                        includeLimecraftSuffix
                );
                currentGameProcess = process;
                Platform.runLater(() -> {
                    versionsList.refresh();
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

    private String safeFolderName(String versionId) {
        return versionId.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void ensureInheritedDependenciesInstalled(JsonObject meta) throws Exception {
        ensureInheritedDependenciesInstalled(meta, new HashSet<>());
    }

    private void ensureInheritedDependenciesInstalled(JsonObject meta, Set<String> visiting) throws Exception {
        if (meta == null || !meta.has("inheritsFrom")) {
            return;
        }
        String parentId = meta.get("inheritsFrom").getAsString();
        if (!visiting.add(parentId)) {
            throw new IllegalStateException("Cyclic version inheritance detected at " + parentId);
        }

        Path parentJson = gameDir.resolve("versions").resolve(parentId).resolve(parentId + ".json");
        Path parentJar = gameDir.resolve("versions").resolve(parentId).resolve(parentId + ".jar");
        if (!Files.exists(parentJson)) {
            VersionEntry parent = findVersionById(parentId);
            if (parent == null || parent.url() == null || parent.url().isBlank()) {
                throw new IllegalStateException("Missing inherited version metadata for " + parentId);
            }
            setStatus("Installing inherited base " + parentId + "...", 0.4);
            installService.installVersion(parent, this::setStatus);
        } else if (!Files.exists(parentJar)) {
            VersionEntry parent = findVersionById(parentId);
            if (parent != null && parent.url() != null && !parent.url().isBlank()) {
                setStatus("Installing missing inherited jar/assets for " + parentId + "...", 0.45);
                installService.installVersion(parent, this::setStatus);
            }
        }

        JsonObject parentMeta = JsonParser.parseString(Files.readString(parentJson)).getAsJsonObject();
        installService.installMetadataDependencies(parentMeta, this::setStatus);
        ensureInheritedDependenciesInstalled(parentMeta, visiting);
        visiting.remove(parentId);
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
        Path json = versionDir.resolve(version.id() + ".json");
        if (!Files.exists(json)) {
            return false;
        }
        Path jar = versionDir.resolve(version.id() + ".jar");
        if (Files.exists(jar)) {
            return true;
        }
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (!meta.has("inheritsFrom")) {
                return false;
            }
            String parent = meta.get("inheritsFrom").getAsString();
            Path parentJar = gameDir.resolve("versions").resolve(parent).resolve(parent + ".jar");
            return Files.exists(parentJar);
        } catch (Exception ex) {
            return false;
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
            String suffixSetting = props.getProperty(KEY_BRANDING_SUFFIX);
            if (suffixSetting != null && !suffixSetting.isBlank()) {
                includeLimecraftSuffix = Boolean.parseBoolean(suffixSetting);
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
            props.setProperty(KEY_BRANDING_SUFFIX, String.valueOf(includeLimecraftSuffix));
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
        if (isGameRunning()) {
            killRunningGame();
        }
        if (isServerRunning()) {
            killRunningServer();
        }
        io.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}





















































































