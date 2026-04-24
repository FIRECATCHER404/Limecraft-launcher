package com.limecraft.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limecraft.launcher.auth.AccountStore;
import com.limecraft.launcher.auth.DeviceCodeInfo;
import com.limecraft.launcher.auth.MicrosoftAuthService;
import com.limecraft.launcher.auth.MinecraftAccount;
import com.limecraft.launcher.auth.SavedMicrosoftAccount;
import com.limecraft.launcher.auth.WindowsDpapiTokenStore;
import com.limecraft.launcher.core.AppPaths;
import com.limecraft.launcher.core.AppVersion;
import com.limecraft.launcher.core.BackupService;
import com.limecraft.launcher.core.CrashReportAnalyzer;
import com.limecraft.launcher.core.HttpClient;
import com.limecraft.launcher.core.JavaRuntimeService;
import com.limecraft.launcher.core.LauncherJobExecutor;
import com.limecraft.launcher.core.MinecraftInstallService;
import com.limecraft.launcher.core.MinecraftLaunchService;
import com.limecraft.launcher.core.VersionEntry;
import com.limecraft.launcher.mod.ModrinthService;
import com.limecraft.launcher.modloader.LoaderFamily;
import com.limecraft.launcher.modloader.ModloaderInstallRequest;
import com.limecraft.launcher.modloader.ModloaderInstallResult;
import com.limecraft.launcher.modloader.ModloaderInstallService;
import com.limecraft.launcher.modloader.ProfileSide;
import com.limecraft.launcher.profile.ProfileMetadata;
import com.limecraft.launcher.profile.ProfileMetadataStore;
import com.limecraft.launcher.shell.LauncherShell;
import com.limecraft.launcher.server.ServerProfileSettings;
import com.limecraft.launcher.server.ServerProfileStore;
import com.limecraft.launcher.update.LauncherUpdateService;
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
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class LimecraftApp extends Application {
    private static final String MODE_MICROSOFT = "Microsoft";
    private static final String MODE_OFFLINE = "Offline";
    private static final String SETTINGS_FILE = "launcher.properties";
    private static final String KEY_LAST_VERSION = "last_selected_version";
    private static final String KEY_OFFLINE_USERNAME = "offline_username";
    private static final String KEY_ACCOUNT_MODE = "account_mode";
    private static final String KEY_RECENT_VERSIONS = "recent_versions";
    private static final String KEY_BRANDING_SUFFIX = "include_limecraft_suffix";
    private static final String KEY_MICROSOFT_REFRESH_TOKEN = "microsoft_refresh_token";
    private static final String KEY_SELECTED_ACCOUNT_ID = "selected_account_id";
    private static final String MICROSOFT_CLIENT_ID = "ba5cdd7c-bc36-4702-9613-35f14f83e52c";
    private static final String JAVA_PATCH_NOTES_INDEX_URL = "https://launchercontent.mojang.com/v2/javaPatchNotes.json";
    private static final String JAVA_PATCH_NOTES_BASE_URL = "https://launchercontent.mojang.com/v2/";
    private static final boolean SHARED_CLIENT_WORKSPACE = false;

    private final AppPaths appPaths = AppPaths.detect();
    private final LauncherJobExecutor io = new LauncherJobExecutor(4);
    private final Path gameDir = appPaths.dataDir();
    private final HttpClient http = new HttpClient();
    private final MinecraftInstallService installService = new MinecraftInstallService(http, gameDir, appPaths.legacyDataDir());
    private final MinecraftLaunchService launchService = new MinecraftLaunchService(gameDir, appPaths.legacyDataDir());
    private final AccountStore accountStore = new AccountStore(gameDir, new WindowsDpapiTokenStore(gameDir));
    private final ProfileMetadataStore profileMetadataStore = new ProfileMetadataStore(gameDir);
    private final ServerProfileStore serverProfileStore = new ServerProfileStore();
    private final BackupService backupService = new BackupService(gameDir);
    private final CrashReportAnalyzer crashReportAnalyzer = new CrashReportAnalyzer();
    private final JavaRuntimeService javaRuntimeService = new JavaRuntimeService();
    private final ModrinthService modrinthService = new ModrinthService(http.raw());
    private final ModloaderInstallService modloaderInstallService = new ModloaderInstallService(http, installService, gameDir);
    private final LauncherUpdateService updateService = new LauncherUpdateService(http.raw());

    private MinecraftAccount signedInAccount;
    private Stage primaryStage;
    private LauncherShell launcherShell;
    private LauncherUpdateService.ReleaseInfo availableUpdate;
    private String lastLauncherErrorSummary = "";

    private ListView<VersionEntry> versionsList;
    private Label status;
    private TextArea logOutput;
    private ProgressBar progress;
    private Label accountLabel;
    private ComboBox<SavedMicrosoftAccount> savedAccountsBox;
    private Button useSavedAccountButton;
    private Button signOutButton;
    private Button removeAccountButton;
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
    private Button installModloaderButton;
    private Button repairButton;
    private Button openModBrowserButton;
    private Button launchButton;
    private Button instanceSettingsButton;
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
    private Button serverRepairButton;
    private Button serverModBrowserButton;
    private Label serverSelectedVersionLabel;
    private ProgressBar serverProgress;
    private TextArea serverLogOutput;
    private TextField serverCommandField;
    private Button serverSendButton;
    private BufferedWriter serverCommandWriter;
    private Process currentServerProcess;
    private List<Control> serverControlsToDisableOnRun = List.of();
    private Process currentGameProcess;
    private List<Control> controlsToDisableOnRun = List.of();
    private List<VersionEntry> allVersions = List.of();
    private String lastSelectedVersionId;
    private String savedOfflineUsername = "Player";
    private String savedAccountMode = MODE_MICROSOFT;
    private String savedMicrosoftRefreshToken = "";
    private String selectedAccountId = "";
    private volatile boolean microsoftSignInInProgress;
    private volatile boolean loadingServerSettings;
    private volatile boolean legacyManagedVersionImportAttempted;
    private volatile boolean legacyInstanceImportAttempted;
    private boolean includeLimecraftSuffix = true;
    private final List<String> recentVersions = new ArrayList<>();
    private long currentGameLaunchStartedAtMillis;
    private String currentGameLaunchVersionId;
    private volatile Map<String, ProfileMetadata> profileMetadataCache = Map.of();
    private volatile Map<String, Boolean> installedClientVersionCache = new LinkedHashMap<>();
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_DEC_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern HTML_HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        loadSettings();

        versionsList = new ListView<>();
        versionsList.setFixedCellSize(28);
        versionsList.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        versionsList.setCellFactory(list -> new ListCell<>() {
            private final Label text = new Label();
            private final Region spacer = new Region();
            private final Region dot = new Region();
            private final HBox row = new HBox(8, text, spacer, dot);
            private final MenuItem deleteItem = new MenuItem("Delete Version");
            private final MenuItem editItem = new MenuItem("Edit Version");
            private final MenuItem customInstanceSettingsItem = new MenuItem("Instance Settings");
            private final MenuItem customOpenInstanceItem = new MenuItem("Open Instance Folder");
            private final MenuItem customOpenWorldsItem = new MenuItem("Open Worlds Folder");
            private final MenuItem customTransferWorldItem = new MenuItem("Transfer World...");
            private final ContextMenu customMenu = new ContextMenu(
                    deleteItem,
                    editItem,
                    new SeparatorMenuItem(),
                    customInstanceSettingsItem,
                    customOpenInstanceItem,
                    customOpenWorldsItem,
                    customTransferWorldItem
            );
            private final MenuItem fabricDeleteItem = new MenuItem("Delete Version");
            private final MenuItem openModsFolderItem = new MenuItem("Open Mods Folder");
            private final MenuItem fabricInstanceSettingsItem = new MenuItem("Instance Settings");
            private final MenuItem fabricOpenInstanceItem = new MenuItem("Open Instance Folder");
            private final MenuItem fabricOpenWorldsItem = new MenuItem("Open Worlds Folder");
            private final MenuItem fabricTransferWorldItem = new MenuItem("Transfer World...");
            private final ContextMenu fabricMenu = new ContextMenu(
                    fabricDeleteItem,
                    openModsFolderItem,
                    new SeparatorMenuItem(),
                    fabricInstanceSettingsItem,
                    fabricOpenInstanceItem,
                    fabricOpenWorldsItem,
                    fabricTransferWorldItem
            );
            private final MenuItem duplicateItem = new MenuItem("Duplicate Version");
            private final MenuItem showChangelogItem = new MenuItem("Show Changelog");
            private final MenuItem builtinInstanceSettingsItem = new MenuItem("Instance Settings");
            private final MenuItem builtinOpenInstanceItem = new MenuItem("Open Instance Folder");
            private final MenuItem builtinOpenWorldsItem = new MenuItem("Open Worlds Folder");
            private final MenuItem builtinTransferWorldItem = new MenuItem("Transfer World...");
            private final ContextMenu builtinMenu = new ContextMenu(
                    duplicateItem,
                    showChangelogItem,
                    new SeparatorMenuItem(),
                    builtinInstanceSettingsItem,
                    builtinOpenInstanceItem,
                    builtinOpenWorldsItem,
                    builtinTransferWorldItem
            );

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
                customInstanceSettingsItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openInstanceSettingsDialog(selected);
                    }
                });
                customOpenInstanceItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openInstanceFolder(selected);
                    }
                });
                customOpenWorldsItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openWorldsFolder(selected);
                    }
                });
                customTransferWorldItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openWorldTransferDialog(selected);
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
                fabricInstanceSettingsItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openInstanceSettingsDialog(selected);
                    }
                });
                fabricOpenInstanceItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openInstanceFolder(selected);
                    }
                });
                fabricOpenWorldsItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openWorldsFolder(selected);
                    }
                });
                fabricTransferWorldItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openWorldTransferDialog(selected);
                    }
                });
                duplicateItem.setOnAction(e -> {
                    setStatus("Manual custom versions are disabled because the client workspace is shared.", 0);
                });
                showChangelogItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        showVersionChangelog(selected);
                    }
                });
                builtinInstanceSettingsItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openInstanceSettingsDialog(selected);
                    }
                });
                builtinOpenInstanceItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openInstanceFolder(selected);
                    }
                });
                builtinOpenWorldsItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openWorldsFolder(selected);
                    }
                });
                builtinTransferWorldItem.setOnAction(e -> {
                    VersionEntry selected = getItem();
                    if (selected != null) {
                        openWorldTransferDialog(selected);
                    }
                });
                duplicateItem.setDisable(SHARED_CLIENT_WORKSPACE);
                if (SHARED_CLIENT_WORKSPACE) {
                    builtinMenu.getItems().remove(duplicateItem);
                }
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
                text.setText(formatVersionLabel(item));
                boolean installed = isVersionInstalled(item);
                if (installed) {
                    dot.getStyleClass().remove("installed-dot-off");
                } else if (!dot.getStyleClass().contains("installed-dot-off")) {
                    dot.getStyleClass().add("installed-dot-off");
                }
                boolean hasInstance = hasLaunchedVersion(item);
                boolean hasWorlds = hasWorldsFolder(item);
                customInstanceSettingsItem.setDisable(false);
                customOpenInstanceItem.setDisable(!hasInstance);
                customOpenWorldsItem.setDisable(!hasWorlds);
                customTransferWorldItem.setDisable(!hasWorlds);

                fabricInstanceSettingsItem.setDisable(false);
                fabricOpenInstanceItem.setDisable(!hasInstance);
                fabricOpenWorldsItem.setDisable(!hasWorlds);
                fabricTransferWorldItem.setDisable(!hasWorlds);
                openModsFolderItem.setDisable(!hasInstance);

                builtinInstanceSettingsItem.setDisable(false);
                builtinOpenInstanceItem.setDisable(!hasInstance);
                builtinOpenWorldsItem.setDisable(!hasWorlds);
                builtinTransferWorldItem.setDisable(!hasWorlds);
                setText(null);
                setGraphic(row);
                if (isModdedVersion(item)) {
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
        savedAccountsBox = new ComboBox<>();
        savedAccountsBox.setPromptText("Saved Microsoft accounts");
        savedAccountsBox.setMaxWidth(Double.MAX_VALUE);
        savedAccountsBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            selectedAccountId = newVal == null ? "" : newVal.profileId();
            saveSettings();
            updateAccountIndicator();
        });
        useSavedAccountButton = new Button("Use Saved");
        useSavedAccountButton.setOnAction(e -> restoreSelectedAccountSession());
        signOutButton = new Button("Sign Out");
        signOutButton.setOnAction(e -> signOutCurrentAccount());
        removeAccountButton = new Button("Remove");
        removeAccountButton.setOnAction(e -> removeSelectedAccount());
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
                updateAccountIndicator();
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

        installModloaderButton = new Button("Install Modloader");
        installModloaderButton.setOnAction(e -> openModloaderInstallDialog());
        repairButton = new Button("Repair Files");
        repairButton.setOnAction(e -> repairSelectedVersion());
        openModBrowserButton = new Button("Browse Mods");
        openModBrowserButton.setOnAction(e -> openModBrowser("client"));

        launchButton = new Button("Launch");
        launchButton.getStyleClass().add("launch-button");
        launchButton.setOnAction(e -> launchSelected());
        instanceSettingsButton = new Button("Instance Settings");
        instanceSettingsButton.setOnAction(e -> {
            VersionEntry selected = versionsList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                setStatus("Select a version to edit instance settings.", 0);
                return;
            }
            openInstanceSettingsDialog(selected);
        });

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

        VBox actionButtons = new VBox(8,
                launchButton,
                repairButton,
                openModBrowserButton,
                instanceSettingsButton,
                selectedVersionLabel,
                labeledNode("Recent", recentBox),
                limecraftSuffixToggle
        );
        launchButton.setMaxWidth(Double.MAX_VALUE);
        signInButton.setMaxWidth(Double.MAX_VALUE);
        instanceSettingsButton.setMaxWidth(Double.MAX_VALUE);
        repairButton.setMaxWidth(Double.MAX_VALUE);
        openModBrowserButton.setMaxWidth(Double.MAX_VALUE);
        useSavedAccountButton.setMaxWidth(Double.MAX_VALUE);
        signOutButton.setMaxWidth(Double.MAX_VALUE);
        removeAccountButton.setMaxWidth(Double.MAX_VALUE);

        GridPane savedAccountActions = new GridPane();
        savedAccountActions.setHgap(8);
        savedAccountActions.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints savedAccountCol1 = new ColumnConstraints();
        savedAccountCol1.setHgrow(Priority.ALWAYS);
        savedAccountCol1.setFillWidth(true);
        savedAccountCol1.setPercentWidth(33.333);
        ColumnConstraints savedAccountCol2 = new ColumnConstraints();
        savedAccountCol2.setHgrow(Priority.ALWAYS);
        savedAccountCol2.setFillWidth(true);
        savedAccountCol2.setPercentWidth(33.333);
        ColumnConstraints savedAccountCol3 = new ColumnConstraints();
        savedAccountCol3.setHgrow(Priority.ALWAYS);
        savedAccountCol3.setFillWidth(true);
        savedAccountCol3.setPercentWidth(33.334);
        savedAccountActions.getColumnConstraints().addAll(savedAccountCol1, savedAccountCol2, savedAccountCol3);
        savedAccountActions.add(useSavedAccountButton, 0, 0);
        savedAccountActions.add(signOutButton, 1, 0);
        savedAccountActions.add(removeAccountButton, 2, 0);

        VBox microsoftAuthBox = new VBox(8,
                signInButton,
                labeledNode("Saved Accounts", savedAccountsBox),
                savedAccountActions,
                accountLabel
        );
        VBox offlineAuthBox = new VBox(8,
                labeledNode("Offline Username", offlineUsernameField)
        );

        accountModeBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean microsoft = MODE_MICROSOFT.equals(newVal);
            setModeVisibility(microsoftAuthBox, offlineAuthBox, microsoft);
        });

        VBox accountCard = new VBox(8,
                labeledNode("Account Mode", accountModeBox),
                microsoftAuthBox,
                offlineAuthBox
        );
        accountCard.getStyleClass().add("settings-card");
        accountCard.setMinWidth(0);
        accountCard.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(accountCard, Priority.NEVER);

        VBox searchCard = new VBox(8,
                labeledNode("Search Versions", searchField),
                experimentToggle
        );
        searchCard.getStyleClass().add("settings-card");
        searchCard.setMinWidth(0);
        searchCard.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(searchCard, Priority.NEVER);

        VBox javaCard = new VBox(8,
                javaPathNode(),
                labeledNode("Max Memory (-Xmx)", ramField)
        );
        javaCard.getStyleClass().add("settings-card");
        javaCard.setMinWidth(0);
        javaCard.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(javaCard, Priority.NEVER);

        VBox actionCard = new VBox(8, actionButtons);
        actionCard.getStyleClass().add("settings-card");
        actionCard.setMinWidth(0);
        actionCard.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(actionCard, Priority.NEVER);

        VBox left = new VBox(12,
                title("Limecraft"),
                accountCard,
                searchCard,
                javaCard,
                actionCard
        );
        setModeVisibility(microsoftAuthBox, offlineAuthBox, MODE_MICROSOFT.equals(accountModeBox.getValue()));
        left.setPrefWidth(340);
        left.setMinWidth(0);
        left.setMaxWidth(Double.MAX_VALUE);
        left.setFillWidth(true);

        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScroll.setPannable(true);
        leftScroll.getStyleClass().add("sidebar-scroll");
        leftScroll.setPrefWidth(380);
        leftScroll.setMinWidth(320);

        Label versionsLabel = new Label("Minecraft Versions");
        VBox right = new VBox(8, addVersionButton, installModloaderButton, versionsLabel, versionsList);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(versionsList, Priority.ALWAYS);
        right.setMaxWidth(Double.MAX_VALUE);
        right.setMinWidth(0);
        versionsList.setMinWidth(0);

        HBox rootRow = new HBox(18, leftScroll, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        rootRow.setMaxWidth(Double.MAX_VALUE);
        rootRow.setMaxHeight(Double.MAX_VALUE);
        rootRow.setMinWidth(0);
        rootRow.setFillHeight(true);

        versionsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            selectedVersionLabel.setText(newVal == null ? "Selected: none" : "Selected: " + newVal.id());
            if (newVal != null) {
                lastSelectedVersionId = newVal.id();
                saveSettings();
            }
            updateClientModBrowserButtonState();
        });

        controlsToDisableOnRun = List.of(
                accountModeBox,
                signInButton,
                savedAccountsBox,
                useSavedAccountButton,
                signOutButton,
                removeAccountButton,
                offlineUsernameField,
                searchField,
                experimentToggle,
                javaPathField,
                detectJavaButton,
                addVersionButton,
                installModloaderButton,
                repairButton,
                openModBrowserButton,
                instanceSettingsButton,
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

        launcherShell = new LauncherShell(stage, AppVersion.APP_NAME, tabPane);
        launcherShell.setUpdateAction(this::triggerAvailableUpdate);
        launcherShell.setErrorAction(this::reportLauncherError);
        io.setActivityListener(this::updateJobIndicator);

        Scene scene = new Scene(launcherShell.root(), 980, 640);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(AppVersion.APP_NAME);
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/limecraft.png")));
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMinWidth(920);
        stage.setMinHeight(620);
        stage.centerOnScreen();
        stage.show();
        Platform.runLater(() -> {
            stage.toFront();
            stage.requestFocus();
        });

        appendLog("[Limecraft] Using " + appPaths.storageModeLabel() + " storage at " + gameDir.toAbsolutePath());
        migrateLegacyAuthStateIfNeeded();
        updateAccountIndicator();
        updateUpdateIndicator();
        updateClientModBrowserButtonState();
        updateServerModBrowserButtonState();
        refreshSavedAccountsBox();
        refreshVersions();
        restoreSavedMicrosoftSession();
        checkForLauncherUpdates();
    }

    private Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("title");
        return l;
    }

    private void migrateLegacyAuthStateIfNeeded() {
        if (!appPaths.portableData()) {
            return;
        }
        Path legacyDir = appPaths.legacyDataDir();
        if (gameDir.equals(legacyDir) || !Files.isDirectory(legacyDir)) {
            return;
        }
        boolean migratedAnything = false;
        try {
            Files.createDirectories(gameDir);

            Path currentAccounts = gameDir.resolve("accounts.json");
            Path legacyAccounts = legacyDir.resolve("accounts.json");
            if (!Files.exists(currentAccounts) && Files.isRegularFile(legacyAccounts)) {
                Files.copy(legacyAccounts, currentAccounts, StandardCopyOption.REPLACE_EXISTING);
                migratedAnything = true;
                appendLog("[Limecraft] Imported saved accounts from " + legacyDir);
            }

            Path currentSecureTokens = gameDir.resolve("secure-tokens.properties");
            Path legacySecureTokens = legacyDir.resolve("secure-tokens.properties");
            if (!Files.exists(currentSecureTokens) && Files.isRegularFile(legacySecureTokens)) {
                Files.copy(legacySecureTokens, currentSecureTokens, StandardCopyOption.REPLACE_EXISTING);
                migratedAnything = true;
                appendLog("[Limecraft] Imported saved secure tokens from " + legacyDir);
            }

            if (selectedAccountId == null || selectedAccountId.isBlank()) {
                String migratedSelectedAccountId = readSelectedAccountIdFrom(legacyDir.resolve(SETTINGS_FILE));
                if (migratedSelectedAccountId != null && !migratedSelectedAccountId.isBlank()) {
                    selectedAccountId = migratedSelectedAccountId;
                    migratedAnything = true;
                    appendLog("[Limecraft] Restored saved account selection from " + legacyDir);
                }
            }

            if (migratedAnything) {
                saveSettings();
            }
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to import saved auth state from " + legacyDir + ": " + ex.getMessage());
        }
    }

    private String readSelectedAccountIdFrom(Path settingsFile) {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) {
            return "";
        }
        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(settingsFile)) {
                props.load(in);
            }
            String value = props.getProperty(KEY_SELECTED_ACCOUNT_ID, "");
            return value == null ? "" : value.trim();
        } catch (Exception ex) {
            return "";
        }
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

    private String readEditableComboValue(ComboBox<String> comboBox) {
        if (comboBox == null) {
            return "";
        }
        String editorText = comboBox.isEditable() && comboBox.getEditor() != null
                ? comboBox.getEditor().getText()
                : "";
        if (editorText != null && !editorText.trim().isBlank()) {
            return editorText.trim();
        }
        String value = comboBox.getValue();
        return value == null ? "" : value.trim();
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
        serverVersionsList.setFixedCellSize(28);
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
                text.setText(formatVersionLabel(item));
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
        serverRepairButton = new Button("Repair Files");
        serverRepairButton.setMaxWidth(Double.MAX_VALUE);
        serverRepairButton.setOnAction(e -> repairSelectedServer());
        serverModBrowserButton = new Button("Browse Mods");
        serverModBrowserButton.setMaxWidth(Double.MAX_VALUE);
        serverModBrowserButton.setOnAction(e -> openModBrowser("server"));
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
                serverRepairButton,
                serverModBrowserButton,
                serverLaunchButton,
                serverSelectedVersionLabel
        );
        left.setPrefWidth(340);
        left.setMinWidth(0);
        left.setMaxWidth(Double.MAX_VALUE);
        left.setFillWidth(true);

        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScroll.setPannable(true);
        leftScroll.getStyleClass().add("sidebar-scroll");
        leftScroll.setPrefWidth(380);
        leftScroll.setMinWidth(320);

        Label versionsLabel = new Label("Minecraft Versions");
        VBox right = new VBox(8, versionsLabel, serverVersionsList);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(serverVersionsList, Priority.ALWAYS);
        right.setMaxWidth(Double.MAX_VALUE);
        right.setMinWidth(0);
        serverVersionsList.setMinWidth(0);

        HBox row = new HBox(18, leftScroll, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setMaxHeight(Double.MAX_VALUE);
        row.setMinWidth(0);
        row.setFillHeight(true);

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
            loadServerSettingsForSelection(newVal);
            updateServerModBrowserButtonState();
        });

        serverJavaPathField.textProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverRamField.textProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverPortField.textProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverMotdField.textProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverMaxPlayersField.textProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverOnlineModeToggle.selectedProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverPvpToggle.selectedProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverCommandBlocksToggle.selectedProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());
        serverNoguiToggle.selectedProperty().addListener((obs, oldVal, newVal) -> persistSelectedServerSettings());

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
                serverRepairButton,
                serverModBrowserButton,
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

        TableView<JavaRuntimeService.JavaRuntime> table = new TableView<>();
        table.getStyleClass().add("java-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<JavaRuntimeService.JavaRuntime, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().version()));
        versionCol.setPrefWidth(120);

        TableColumn<JavaRuntimeService.JavaRuntime, String> archCol = new TableColumn<>("Architecture");
        archCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().architecture()));
        archCol.setPrefWidth(110);

        TableColumn<JavaRuntimeService.JavaRuntime, String> pathCol = new TableColumn<>("Path");
        pathCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().path()));
        pathCol.setPrefWidth(320);

        TableColumn<JavaRuntimeService.JavaRuntime, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().source()));
        sourceCol.setPrefWidth(120);

        table.getColumns().addAll(versionCol, archCol, pathCol, sourceCol);

        VersionEntry selectedVersion = versionsList == null ? null : versionsList.getSelectionModel().getSelectedItem();
        int recommendedMajor = javaRuntimeService.recommendJavaMajor(selectedVersion == null ? null : selectedVersion.id());
        Label recommendation = new Label("Recommended Java: " + recommendedMajor + "+ for " + (selectedVersion == null ? "current profile" : selectedVersion.id()));
        recommendation.getStyleClass().add("selected-version");

        Runnable refresh = () -> {
            List<JavaRuntimeService.JavaRuntime> items = javaRuntimeService.detectInstalledJavas();
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
            JavaRuntimeService.JavaRuntime selected = table.getSelectionModel().getSelectedItem();
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

        VBox root = new VBox(10, recommendation, table, buttons);
        root.getStyleClass().add("java-popup-root");
        root.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 760, 420);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }
    private record InstanceSettings(String javaPath, String xmx, boolean hideCustomSuffix) {}
    private record ModBrowserContext(VersionEntry target, String side, String loader, String gameVersion) {}

    private void openAddVersionDialog() {
        openAddVersionDialog(null, null);
    }

    private void openAddVersionDialog(VersionEntry duplicateBase, VersionEntry editingVersion) {
        if (SHARED_CLIENT_WORKSPACE) {
            setStatus("Manual custom versions are disabled because the client workspace is shared.", 0);
            return;
        }
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(editingVersion != null ? "Edit Version" : "Add Version");

        TextField nameField = new TextField();
        nameField.setPromptText("my-custom-version");

        List<VersionEntry> clientVersions = allVersions.stream()
                .filter(VersionEntry::supportsClient)
                .toList();
        ComboBox<VersionEntry> baseBox = new ComboBox<>(FXCollections.observableArrayList(clientVersions));
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
        jarField.setPromptText("Optional jar / JAR mod path");
        Button browseJar = new Button("Browse");
        browseJar.setOnAction(e -> {
            Path p = chooseFile("Select Jar", "Jar Files", "*.jar");
            if (p != null) {
                jarField.setText(p.toString());
            }
        });
        CheckBox addToJarMode = new CheckBox("Add to JAR (use base version)");
        CheckBox replaceJarMode = new CheckBox("Replace JAR (use base manifest)");
        addToJarMode.setSelected(true);
        addToJarMode.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (Boolean.TRUE.equals(newVal)) {
                replaceJarMode.setSelected(false);
            }
        });
        replaceJarMode.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (Boolean.TRUE.equals(newVal)) {
                addToJarMode.setSelected(false);
            }
        });

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
        VBox modeBox = new VBox(6, addToJarMode, replaceJarMode);
        form.add(modeBox, 1, 4);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(110);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);

        Label note = new Label("Add to JAR merges the selected JAR on top of a base. Replace JAR uses the base manifest.");
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
            boolean addToJar = addToJarMode.isSelected();
            boolean replaceJar = replaceJarMode.isSelected();
            if ((addToJar || replaceJar) && base == null) {
                fail(new IllegalArgumentException("Select a base version for Add/Replace JAR mode."));
                return;
            }
            if (replaceJar && jar == null) {
                fail(new IllegalArgumentException("Select a JAR to replace with."));
                return;
            }
            dialog.close();

            io.submit(() -> {
                try {
                    createCustomVersion(customId, base, manifest, jar, addToJar, replaceJar);
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

    private void openModloaderInstallDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Install Modloader");

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
        VersionEntry suggestedBase = resolveSuggestedBaseVersion(selected);
        if (suggestedBase != null) {
            baseBox.setValue(suggestedBase);
        } else if (!official.isEmpty()) {
            baseBox.getSelectionModel().select(0);
        }

        ComboBox<ProfileSide> sideBox = new ComboBox<>(FXCollections.observableArrayList(ProfileSide.values()));
        sideBox.setValue(ProfileSide.CLIENT);
        ComboBox<LoaderFamily> familyBox = new ComboBox<>(FXCollections.observableArrayList(LoaderFamily.values()));
        familyBox.setValue(LoaderFamily.FABRIC);
        TextField nameField = new TextField();
        nameField.setPromptText("Optional custom name");
        ComboBox<String> loaderVersionBox = new ComboBox<>();
        loaderVersionBox.setEditable(true);
        loaderVersionBox.setMaxWidth(Double.MAX_VALUE);
        loaderVersionBox.setPromptText("Loading loader versions...");
        Label loaderVersionStatus = new Label("Loading loader versions...");
        loaderVersionStatus.getStyleClass().add("selected-version");
        final long[] loaderVersionRequestId = new long[] { 0L };

        Label note = new Label("Client and server support: Fabric, Quilt, Forge, and NeoForge.");
        note.getStyleClass().add("selected-version");

        Runnable syncState = () -> {
            LoaderFamily family = familyBox.getValue();
            ProfileSide side = sideBox.getValue();
            boolean supported = family != null && side != null && family.supports(side);
            note.setText(supported
                    ? ("Install " + family.displayName() + " for " + side.toString().toLowerCase(Locale.ROOT) + ".")
                    : (family.displayName() + " " + side.toString().toLowerCase(Locale.ROOT) + " install is not available yet."));
        };
        sideBox.valueProperty().addListener((obs, oldVal, newVal) -> syncState.run());
        Runnable refreshLoaderVersions = () -> {
            VersionEntry base = baseBox.getValue();
            LoaderFamily family = familyBox.getValue();
            String currentText = readEditableComboValue(loaderVersionBox);
            if (base == null || family == null) {
                loaderVersionBox.setItems(FXCollections.emptyObservableList());
                loaderVersionBox.setDisable(true);
                loaderVersionBox.getEditor().clear();
                loaderVersionStatus.setText("Select a loader and Minecraft version first.");
                return;
            }

            long requestId = ++loaderVersionRequestId[0];
            loaderVersionBox.setDisable(true);
            loaderVersionBox.setItems(FXCollections.emptyObservableList());
            loaderVersionBox.setPromptText("Loading loader versions...");
            loaderVersionStatus.setText("Loading " + family.displayName() + " versions for Minecraft " + base.id() + "...");
            io.submit(() -> {
                try {
                    List<String> versions = modloaderInstallService.listAvailableLoaderVersions(family, base.id());
                    Platform.runLater(() -> {
                        if (loaderVersionRequestId[0] != requestId) {
                            return;
                        }
                        loaderVersionBox.setDisable(false);
                        loaderVersionBox.setItems(FXCollections.observableArrayList(versions));
                        if (!versions.isEmpty()) {
                            if (!currentText.isBlank()) {
                                loaderVersionBox.getSelectionModel().select(currentText);
                                loaderVersionBox.getEditor().setText(currentText);
                            } else {
                                loaderVersionBox.getSelectionModel().selectFirst();
                            }
                            loaderVersionStatus.setText("Loaded " + versions.size() + " " + family.displayName() + " versions for Minecraft " + base.id() + ".");
                        } else {
                            loaderVersionBox.getEditor().clear();
                            loaderVersionBox.setPromptText("Enter loader version manually");
                            loaderVersionStatus.setText("No versions were returned. Enter a loader version manually.");
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (loaderVersionRequestId[0] != requestId) {
                            return;
                        }
                        loaderVersionBox.setDisable(false);
                        loaderVersionBox.setItems(FXCollections.emptyObservableList());
                        loaderVersionBox.setPromptText("Enter loader version manually");
                        loaderVersionBox.getEditor().setText(currentText);
                        loaderVersionStatus.setText("Could not load loader versions automatically. Enter one manually.");
                    });
                }
            });
        };
        familyBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            syncState.run();
            refreshLoaderVersions.run();
        });
        baseBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshLoaderVersions.run());
        syncState.run();
        refreshLoaderVersions.run();

        Button install = new Button("Install");
        install.getStyleClass().add("launch-button");
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());

        install.setOnAction(e -> {
            VersionEntry base = baseBox.getValue();
            ProfileSide side = sideBox.getValue();
            LoaderFamily family = familyBox.getValue();
            if (base == null) {
                fail(new IllegalArgumentException("Select a Minecraft version first."));
                return;
            }
            if (family == null || side == null) {
                fail(new IllegalArgumentException("Select a loader and target side first."));
                return;
            }
            if (!family.supports(side)) {
                fail(new IllegalArgumentException(family.displayName() + " " + side.toString().toLowerCase(Locale.ROOT) + " install is not available yet."));
                return;
            }
            String requestedName = nameField.getText() == null ? "" : nameField.getText().trim();
            if (!requestedName.isBlank() && requestedName.matches(".*[\\\\/:*?\"<>|].*")) {
                fail(new IllegalArgumentException("Name contains invalid path characters."));
                return;
            }
            String loaderVersion = readEditableComboValue(loaderVersionBox);
            dialog.close();
            io.submit(() -> {
                try {
                    installModloaderProfile(new ModloaderInstallRequest(
                            family,
                            side,
                            base,
                            requestedName,
                            loaderVersion,
                            side == ProfileSide.SERVER && serverJavaPathField != null
                                    ? serverJavaPathField.getText()
                                    : (javaPathField == null ? "java" : javaPathField.getText())
                    ));
                } catch (Exception ex) {
                    fail(ex);
                }
            });
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.add(new Label("Target Side"), 0, 0);
        form.add(sideBox, 1, 0);
        form.add(new Label("Loader"), 0, 1);
        form.add(familyBox, 1, 1);
        form.add(new Label("Minecraft Version"), 0, 2);
        form.add(baseBox, 1, 2);
        form.add(new Label("Custom Name"), 0, 3);
        form.add(nameField, 1, 3);
        form.add(new Label("Loader Version"), 0, 4);
        form.add(loaderVersionBox, 1, 4);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(120);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, install, cancel);

        VBox root = new VBox(10, form, note, loaderVersionStatus, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 760, 290);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void installModloaderProfile(ModloaderInstallRequest request) throws Exception {
        setStatus("Installing " + request.family().displayName() + " " + request.side().toString().toLowerCase(Locale.ROOT) + " profile...", 0.15);
        ModloaderInstallResult result = modloaderInstallService.install(request, this::setStatus);
        Platform.runLater(() -> {
            registerInstalledVersion(result.versionEntry());
            if (result.versionEntry().supportsClient()) {
                selectVersionById(result.versionEntry().id());
            }
            if (result.versionEntry().supportsServer() && serverVersionsList != null) {
                selectServerVersionById(result.versionEntry().id());
            }
            setStatus("Installed " + request.family().displayName() + " " + request.side().toString().toLowerCase(Locale.ROOT) + " profile " + result.versionEntry().id(), 0);
        });
    }

    private VersionEntry resolveSuggestedBaseVersion(VersionEntry selected) {
        if (selected == null) {
            return null;
        }
        if (selected.url() != null && !selected.url().isBlank()) {
            return selected;
        }
        JsonObject meta = loadVersionMetaQuiet(selected.id());
        if (meta == null || !meta.has("inheritsFrom")) {
            return null;
        }
        return findVersionById(meta.get("inheritsFrom").getAsString());
    }

    private void registerInstalledVersion(VersionEntry version) {
        if (version == null) {
            return;
        }
        List<VersionEntry> updated = new ArrayList<>(allVersions == null ? List.of() : allVersions);
        updated.removeIf(existing -> existing.id().equalsIgnoreCase(version.id()));
        updated.add(version);
        updated.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        installedClientVersionCache.remove(version.id());
        allVersions = updated;
        applyVersionFilter();
        applyServerVersionFilter();
        versionsList.refresh();
        if (serverVersionsList != null) {
            serverVersionsList.refresh();
        }
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

    private void createCustomVersion(String customId, VersionEntry base, Path manifestOverride, Path jarOverride, boolean addToJarMode, boolean replaceJarMode) throws Exception {
        Path targetDir = gameDir.resolve("versions").resolve(customId);
        Files.createDirectories(targetDir);

        Path manifestSource;
        Path jarSource;
        Path baseJarSource = null;
        Path overlayJar = null;

        if (addToJarMode) {
            if (base == null) {
                throw new IllegalStateException("Base version is required for Add to JAR mode.");
            }
            ensureBaseInstalled(base);
            Path baseManifest = gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".json");
            baseJarSource = gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".jar");
            manifestSource = manifestOverride != null ? manifestOverride : baseManifest;
            jarSource = baseJarSource;
            overlayJar = jarOverride;
        } else if (replaceJarMode) {
            if (base == null) {
                throw new IllegalStateException("Base version is required for Replace JAR mode.");
            }
            ensureBaseInstalled(base);
            Path baseManifest = gameDir.resolve("versions").resolve(base.id()).resolve(base.id() + ".json");
            manifestSource = manifestOverride != null ? manifestOverride : baseManifest;
            jarSource = jarOverride;
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
        if (overlayJar != null && !Files.exists(overlayJar)) {
            throw new IllegalStateException("JAR mod file not found: " + overlayJar);
        }

        Path targetManifest = targetDir.resolve(customId + ".json");
        Path targetJar = targetDir.resolve(customId + ".jar");

        Files.copy(manifestSource, targetManifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (addToJarMode && overlayJar != null) {
            mergeJarOverlay(jarSource, overlayJar, targetJar);
        } else {
            Files.copy(jarSource, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        JsonObject meta = JsonParser.parseString(Files.readString(targetManifest)).getAsJsonObject();
        meta.addProperty("id", customId);
        meta.addProperty("type", "custom");
        Files.writeString(targetManifest, meta.toString());
    }

    private void mergeJarOverlay(Path baseJar, Path overlayJar, Path targetJar) throws Exception {
        Map<String, byte[]> mergedEntries = new LinkedHashMap<>();
        collectJarEntries(baseJar, mergedEntries, false);
        collectJarEntries(overlayJar, mergedEntries, true);

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(targetJar))) {
            for (Map.Entry<String, byte[]> entry : mergedEntries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                out.putNextEntry(jarEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private void collectJarEntries(Path jarPath, Map<String, byte[]> entries, boolean overlay) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (shouldSkipJarEntry(name, overlay)) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] data = in.readAllBytes();
                    if (overlay) {
                        entries.put(name, data);
                    } else {
                        entries.putIfAbsent(name, data);
                    }
                }
            }
        }
    }

    private boolean shouldSkipJarEntry(String name, boolean overlay) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        if (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")) {
            return true;
        }
        return overlay && "META-INF/MANIFEST.MF".equals(upper);
    }

    private boolean isCustomVersion(VersionEntry version) {
        return version != null && "custom".equalsIgnoreCase(version.type());
    }

    private boolean isFabricVersion(VersionEntry version) {
        return version != null && "fabric".equalsIgnoreCase(version.type());
    }

    private boolean isManagedVersion(VersionEntry version) {
        return isModdedVersion(version) || isCustomVersion(version);
    }

    private void deleteManagedVersion(VersionEntry version) {
        if (!isManagedVersion(version)) {
            setStatus("Only custom/modded versions can be deleted from the launcher list.", 0);
            return;
        }
        String kind = isCustomVersion(version) ? "custom" : detectLoaderFamily(version);
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
                Path instanceDir = SHARED_CLIENT_WORKSPACE ? null : gameDir.resolve("instances").resolve(version.id().replaceAll("[\\/:*?\"<>|]", "_"));
                Path backup = backupService.snapshotVersion(version.id(), versionDir, instanceDir);
                deleteDirectoryIfExists(versionDir);
                if (instanceDir != null) {
                    deleteDirectoryIfExists(instanceDir);
                }
                Platform.runLater(() -> {
                    installedClientVersionCache.remove(version.id());
                    List<VersionEntry> updated = new ArrayList<>(allVersions);
                    updated.removeIf(v -> v.id().equalsIgnoreCase(version.id()));
                    allVersions = updated;
                    if (version.id().equalsIgnoreCase(lastSelectedVersionId)) {
                        lastSelectedVersionId = null;
                    }
                    saveSettings();
                    applyVersionFilter();
                    applyServerVersionFilter();
                    versionsList.refresh();
                    setStatus("Deleted " + kind + " version " + version.id() + (backup == null ? "" : " (backup saved)"), 0);
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

    private Path instanceDirFor(VersionEntry version) {
        return instanceDirForId(version.id());
    }

    private Path instanceDirForId(String versionId) {
        if (SHARED_CLIENT_WORKSPACE) {
            return gameDir.resolve("client");
        }
        return gameDir.resolve("instances").resolve(safeFolderName(versionId));
    }

    private Path legacyInstanceDirForId(String versionId) {
        if (SHARED_CLIENT_WORKSPACE) {
            return appPaths.legacyDataDir().resolve("client");
        }
        return appPaths.legacyDataDir().resolve("instances").resolve(safeFolderName(versionId));
    }

    private Path existingInstanceDirFor(VersionEntry version) {
        return existingInstanceDirForId(version.id());
    }

    private Path existingInstanceDirForId(String versionId) {
        Path local = instanceDirForId(versionId);
        if (Files.isDirectory(local)) {
            return local;
        }
        Path legacy = legacyInstanceDirForId(versionId);
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        return local;
    }

    private Path worldsDirFor(VersionEntry version) {
        return instanceDirFor(version).resolve("saves");
    }

    private Path existingWorldsDirFor(VersionEntry version) {
        return existingInstanceDirFor(version).resolve("saves");
    }

    private boolean hasLaunchedVersion(VersionEntry version) {
        if (version == null) {
            return false;
        }
        return Files.isDirectory(existingInstanceDirFor(version));
    }

    private boolean hasWorldsFolder(VersionEntry version) {
        if (version == null) {
            return false;
        }
        return Files.isDirectory(existingWorldsDirFor(version));
    }

    private void ensureInstanceAvailableLocally(VersionEntry version) throws Exception {
        if (version == null || SHARED_CLIENT_WORKSPACE) {
            return;
        }
        Path localDir = instanceDirFor(version);
        if (Files.isDirectory(localDir)) {
            return;
        }
        Path legacyDir = legacyInstanceDirForId(version.id());
        if (!Files.isDirectory(legacyDir)) {
            return;
        }
        copyDirectory(legacyDir, localDir);
        appendLog("[Limecraft] Imported legacy instance folder for " + version.id());
    }

    private InstanceSettings loadInstanceSettings(VersionEntry version) {
        Path file = existingInstanceDirFor(version).resolve("instance.properties");
        if (!Files.exists(file)) {
        return new InstanceSettings("", "", false);
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to load instance settings: " + ex.getMessage());
        }
        String javaPath = props.getProperty("java_path", "").trim();
        String xmx = props.getProperty("xmx", "").trim();
        boolean hideCustomSuffix = Boolean.parseBoolean(props.getProperty("hide_custom_suffix", "false").trim());
        return new InstanceSettings(javaPath, xmx, hideCustomSuffix);
    }

    private void saveInstanceSettings(VersionEntry version, InstanceSettings settings) {
        String javaPath = settings.javaPath() == null ? "" : settings.javaPath().trim();
        String xmx = settings.xmx() == null ? "" : settings.xmx().trim();
        boolean hideCustomSuffix = settings.hideCustomSuffix();
        Path dir = instanceDirFor(version);
        Path file = dir.resolve("instance.properties");
        if (javaPath.isBlank() && xmx.isBlank() && !hideCustomSuffix) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ex) {
                appendLog("[Limecraft] Failed to clear instance settings: " + ex.getMessage());
            }
            return;
        }
        try {
            Files.createDirectories(dir);
            Properties props = new Properties();
            if (!javaPath.isBlank()) {
                props.setProperty("java_path", javaPath);
            }
            if (!xmx.isBlank()) {
                props.setProperty("xmx", xmx);
            }
            if (hideCustomSuffix) {
                props.setProperty("hide_custom_suffix", "true");
            }
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "Limecraft instance settings");
            }
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to save instance settings: " + ex.getMessage());
        }
    }

    private void openInstanceSettingsDialog(VersionEntry version) {
        if (version == null) {
            setStatus("Select a version first.", 0);
            return;
        }
        InstanceSettings settings = loadInstanceSettings(version);
        ProfileMetadata metadata = profileMetadataStore.get(version.id());

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Instance Settings: " + version.id());

        TextField javaField = new TextField(settings.javaPath());
        TextField xmxField = new TextField(settings.xmx());
        TextField tagsField = new TextField(metadata.tags());
        TextField groupField = new TextField(metadata.groupName());
        TextField iconField = new TextField(metadata.iconPath());
        boolean isCustom = isCustomVersion(version);
        CheckBox hideCustomSuffix = new CheckBox("Hide 'custom' suffix");
        CheckBox favoriteBox = new CheckBox("Favorite profile");
        hideCustomSuffix.setSelected(settings.hideCustomSuffix());
        hideCustomSuffix.setVisible(isCustom);
        hideCustomSuffix.setManaged(isCustom);
        favoriteBox.setSelected(metadata.favorite());
        TextArea notesArea = new TextArea(metadata.notes());
        notesArea.setPromptText("Profile notes");
        notesArea.setWrapText(true);
        notesArea.setPrefRowCount(5);
        Label pathLabel = new Label(instanceDirFor(version).toString());
        pathLabel.getStyleClass().add("selected-version");
        Label playtimeLabel = new Label("Playtime: " + formatDuration(metadata.playTimeSeconds()));
        playtimeLabel.getStyleClass().add("selected-version");

        Button openInstance = new Button("Open Instance Folder");
        openInstance.setOnAction(e -> openInstanceFolder(version));
        Button openWorlds = new Button("Open Worlds Folder");
        openWorlds.setOnAction(e -> openWorldsFolder(version));
        Button openLogs = new Button("Open Logs");
        openLogs.setOnAction(e -> openLogsFolder(version));
        Button openCrashReports = new Button("Open Crash Reports");
        openCrashReports.setOnAction(e -> openCrashReportsFolder(version));
        Button createSnapshot = new Button("Create Snapshot");
        createSnapshot.setOnAction(e -> createProfileSnapshot(version));
        Button diagnoseCrash = new Button("Diagnose Crash");
        diagnoseCrash.setOnAction(e -> diagnoseLatestCrash(version));
        Button useGlobal = new Button("Use Global");
        useGlobal.setOnAction(e -> {
            javaField.clear();
            xmxField.clear();
            if (isCustom) {
                hideCustomSuffix.setSelected(false);
            }
        });
        Button save = new Button("Save");
        save.getStyleClass().add("launch-button");
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());
        save.setOnAction(e -> {
            saveInstanceSettings(version, new InstanceSettings(javaField.getText(), xmxField.getText(), hideCustomSuffix.isSelected()));
            try {
                ProfileMetadata current = profileMetadataStore.get(version.id());
                profileMetadataStore.save(new ProfileMetadata(
                        version.id(),
                        favoriteBox.isSelected(),
                        notesArea.getText() == null ? "" : notesArea.getText().trim(),
                        tagsField.getText() == null ? "" : tagsField.getText().trim(),
                        groupField.getText() == null ? "" : groupField.getText().trim(),
                        iconField.getText() == null ? "" : iconField.getText().trim(),
                        current.playTimeSeconds(),
                        current.lastPlayedAt()
                ));
                profileMetadataCache = profileMetadataStore.loadAll();
                versionsList.refresh();
                if (serverVersionsList != null) {
                    serverVersionsList.refresh();
                }
            } catch (Exception ex) {
                fail(ex);
                return;
            }
            setStatus("Saved instance settings for " + version.id(), 0);
            dialog.close();
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        int row = 0;
        form.add(new Label("Java Path"), 0, row);
        form.add(javaField, 1, row++);
        form.add(new Label("Max Memory (-Xmx)"), 0, row);
        form.add(xmxField, 1, row++);
        form.add(new Label("Group"), 0, row);
        form.add(groupField, 1, row++);
        form.add(new Label("Tags"), 0, row);
        form.add(tagsField, 1, row++);
        form.add(new Label("Icon Path"), 0, row);
        form.add(iconField, 1, row++);
        form.add(new Label("Favorite"), 0, row);
        form.add(favoriteBox, 1, row++);
        if (isCustom) {
            form.add(new Label("Custom Suffix"), 0, row);
            form.add(hideCustomSuffix, 1, row++);
        }
        form.add(new Label("Playtime"), 0, row);
        form.add(playtimeLabel, 1, row++);
        form.add(new Label("Instance Path"), 0, row);
        form.add(pathLabel, 1, row++);
        form.add(new Label("Notes"), 0, row);
        form.add(notesArea, 1, row);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(130);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);
        GridPane.setVgrow(notesArea, Priority.ALWAYS);

        HBox tools = new HBox(8, openInstance, openWorlds, openLogs, openCrashReports, createSnapshot, diagnoseCrash, useGlobal);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, tools, spacer, save, cancel);

        VBox root = new VBox(12, form, buttons);
        root.setPadding(new Insets(12));
        VBox.setVgrow(form, Priority.ALWAYS);
        Scene scene = new Scene(root, 860, 520);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void openInstanceFolder(VersionEntry version) {
        Path existingDir = existingInstanceDirFor(version);
        Path instanceDir = Files.isDirectory(existingDir) ? existingDir : instanceDirFor(version);
        io.submit(() -> {
            try {
                Files.createDirectories(instanceDir);
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("Desktop integration is not supported on this system.");
                }
                Desktop.getDesktop().open(instanceDir.toFile());
                setStatus("Opened instance folder for " + version.id(), 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void openWorldsFolder(VersionEntry version) {
        Path savesDir = existingWorldsDirFor(version);
        io.submit(() -> {
            try {
                if (!Files.isDirectory(savesDir)) {
                    throw new IllegalStateException("No worlds found for " + version.id() + ". Launch it once first.");
                }
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("Desktop integration is not supported on this system.");
                }
                Desktop.getDesktop().open(savesDir.toFile());
                setStatus("Opened worlds folder for " + version.id(), 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void openWorldTransferDialog(VersionEntry source) {
        if (SHARED_CLIENT_WORKSPACE) {
            setStatus("World transfer is not needed because the client workspace is shared.", 0);
            return;
        }
        if (source == null) {
            setStatus("Select a version first.", 0);
            return;
        }
        Path sourceSaves = existingWorldsDirFor(source);
        if (!Files.isDirectory(sourceSaves)) {
            setStatus("No worlds found for " + source.id() + ". Launch it once first.", 0);
            return;
        }
        List<String> worlds = listWorldNames(sourceSaves);
        if (worlds.isEmpty()) {
            setStatus("No worlds found for " + source.id() + ".", 0);
            return;
        }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Transfer World from " + source.id());

        ListView<String> worldList = new ListView<>(FXCollections.observableArrayList(worlds));
        worldList.getSelectionModel().selectFirst();

        List<VersionEntry> targetVersions = allVersions.stream()
                .filter(VersionEntry::supportsClient)
                .toList();
        ComboBox<VersionEntry> targetBox = new ComboBox<>(FXCollections.observableArrayList(targetVersions));
        targetBox.setPromptText("Select target instance");
        if (!targetVersions.isEmpty()) {
            targetBox.getSelectionModel().select(0);
        }

        Button copyButton = new Button("Copy");
        Button moveButton = new Button("Move");
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> dialog.close());

        copyButton.setOnAction(e -> {
            String world = worldList.getSelectionModel().getSelectedItem();
            VersionEntry target = targetBox.getValue();
            if (world == null || target == null) {
                setStatus("Select a world and a target instance.", 0);
                return;
            }
            if (target.id().equalsIgnoreCase(source.id())) {
                setStatus("Pick a different target instance.", 0);
                return;
            }
            dialog.close();
            transferWorld(source, target, world, false);
        });
        moveButton.setOnAction(e -> {
            String world = worldList.getSelectionModel().getSelectedItem();
            VersionEntry target = targetBox.getValue();
            if (world == null || target == null) {
                setStatus("Select a world and a target instance.", 0);
                return;
            }
            if (target.id().equalsIgnoreCase(source.id())) {
                setStatus("Pick a different target instance.", 0);
                return;
            }
            dialog.close();
            transferWorld(source, target, world, true);
        });

        VBox root = new VBox(10,
                labeledNode("World", worldList),
                labeledNode("Target Instance", targetBox),
                new HBox(8, copyButton, moveButton, cancel)
        );
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 620, 420);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void transferWorld(VersionEntry source, VersionEntry target, String worldName, boolean move) {
        io.submit(() -> {
            try {
                Path sourceDir = worldsDirFor(source).resolve(worldName);
                Path targetSaves = worldsDirFor(target);
                Files.createDirectories(targetSaves);
                Path targetDir = targetSaves.resolve(worldName);
                if (Files.exists(targetDir)) {
                    throw new IllegalStateException("Target instance already has a world named " + worldName + ".");
                }
                if (move) {
                    backupService.snapshotWorld(source.id(), worldName, sourceDir);
                    moveDirectory(sourceDir, targetDir);
                    setStatus("Moved world to " + target.id(), 0);
                } else {
                    copyDirectory(sourceDir, targetDir);
                    setStatus("Copied world to " + target.id(), 0);
                }
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private List<String> listWorldNames(Path savesDir) {
        try (Stream<Path> stream = Files.list(savesDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to list worlds: " + ex.getMessage());
            return List.of();
        }
    }

    private void copyDirectory(Path source, Path target) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path rel = source.relativize(path);
                Path dest = target.resolve(rel);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void moveDirectory(Path source, Path target) throws Exception {
        try {
            Files.move(source, target);
        } catch (Exception ex) {
            copyDirectory(source, target);
            deleteDirectoryIfExists(source);
        }
    }

    private void openFabricModsFolder(VersionEntry version) {
        if (!isModdedVersion(version)) {
            setStatus("Mods folder is only available for modded profiles.", 0);
            return;
        }
        Path instanceDir = instanceDirFor(version);
        Path modsDir = instanceDir.resolve("mods");
        io.submit(() -> {
            try {
                Files.createDirectories(instanceDir);
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

    private boolean isModdedVersion(VersionEntry version) {
        String loader = detectLoaderFamily(version);
        return !"vanilla".equals(loader) && !"custom".equals(loader);
    }

    private String formatVersionLabel(VersionEntry version) {
        ProfileMetadata metadata = profileMetadataCache.getOrDefault(
                version.id(),
                ProfileMetadata.defaults(version.id())
        );
        StringBuilder label = new StringBuilder();
        if (metadata.favorite()) {
            label.append("\u2605 ");
        }
        if (metadata.groupName() != null && !metadata.groupName().isBlank()) {
            label.append("[").append(metadata.groupName().trim()).append("] ");
        }
        label.append(version.toString());
        if (metadata.playTimeSeconds() > 0) {
            label.append(" | ").append(formatDuration(metadata.playTimeSeconds()));
        }
        return label.toString();
    }

    private String formatDuration(long seconds) {
        long totalMinutes = Math.max(0, seconds) / 60;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private String detectLoaderFamily(VersionEntry version) {
        if (version == null) {
            return "vanilla";
        }
        String type = version.type() == null ? "" : version.type().trim().toLowerCase(Locale.ROOT);
        if (type.equals("fabric") || type.equals("quilt") || type.equals("forge") || type.equals("neoforge")) {
            return type;
        }
        if (type.equals("custom")) {
            return "custom";
        }
        if (version.url() != null && !version.url().isBlank()) {
            return "vanilla";
        }
        if (type.equals("release")
                || type.equals("snapshot")
                || type.equals("old_alpha")
                || type.equals("old_beta")
                || type.equals("experiment")
                || type.equals("pending")) {
            return "vanilla";
        }
        JsonObject meta = loadVersionMetaQuiet(version.id());
        if (meta == null) {
            return "vanilla";
        }
        return detectLoaderFamilyFromMetadata(meta, type);
    }

    private String modBrowserLoaderFor(VersionEntry version) {
        String loader = detectLoaderFamily(version);
        return switch (loader) {
            case "fabric", "quilt", "forge", "neoforge", "vanilla" -> loader;
            default -> "vanilla";
        };
    }

    private ModBrowserContext resolveSelectedModBrowserContext(String side) {
        String normalizedSide = "server".equalsIgnoreCase(side) ? "server" : "client";
        VersionEntry target = "server".equals(normalizedSide)
                ? (serverVersionsList == null ? null : serverVersionsList.getSelectionModel().getSelectedItem())
                : (versionsList == null ? null : versionsList.getSelectionModel().getSelectedItem());
        return resolveModBrowserContext(target, normalizedSide);
    }

    private ModBrowserContext resolveModBrowserContext(VersionEntry target, String side) {
        if (target == null || !isModdedVersion(target)) {
            return null;
        }
        String loader = modBrowserLoaderFor(target);
        if ("vanilla".equals(loader)) {
            return null;
        }
        String gameVersion = resolveMinecraftVersionId(target);
        if (gameVersion.isBlank()) {
            return null;
        }
        return new ModBrowserContext(target, "server".equalsIgnoreCase(side) ? "server" : "client", loader, gameVersion);
    }

    private String resolveMinecraftVersionId(VersionEntry version) {
        if (version == null) {
            return "";
        }
        if (version.url() != null && !version.url().isBlank()) {
            return version.id();
        }
        return resolveMinecraftVersionId(version.id(), new HashSet<>());
    }

    private String resolveMinecraftVersionId(String versionId, Set<String> visiting) {
        if (versionId == null || versionId.isBlank() || !visiting.add(versionId)) {
            return "";
        }
        VersionEntry known = findVersionById(versionId);
        if (known != null && known.url() != null && !known.url().isBlank()) {
            return known.id();
        }
        JsonObject meta = loadVersionMetaQuiet(versionId);
        if (meta == null || !meta.has("inheritsFrom") || meta.get("inheritsFrom").isJsonNull()) {
            return versionId;
        }
        try {
            String parentId = meta.get("inheritsFrom").getAsString();
            if (parentId == null || parentId.isBlank()) {
                return versionId;
            }
            return resolveMinecraftVersionId(parentId, visiting);
        } catch (Exception ignored) {
            return versionId;
        }
    }

    private void updateClientModBrowserButtonState() {
        if (openModBrowserButton == null || isGameRunning()) {
            return;
        }
        openModBrowserButton.setDisable(resolveSelectedModBrowserContext("client") == null);
    }

    private void updateServerModBrowserButtonState() {
        if (serverModBrowserButton == null || isServerRunning()) {
            return;
        }
        serverModBrowserButton.setDisable(resolveSelectedModBrowserContext("server") == null);
    }

    private boolean hasLibraryPrefix(JsonObject meta, String prefix) {
        if (meta == null || prefix == null || prefix.isBlank()) {
            return false;
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
            if (name.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private JsonObject loadVersionMetaQuiet(String versionId) {
        try {
            Path versionJson = findVersionJson(versionId);
            if (!Files.exists(versionJson)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path findVersionJson(String versionId) {
        Path localJson = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(localJson)) {
            return localJson;
        }
        Path legacyJson = appPaths.legacyDataDir().resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (Files.exists(legacyJson)) {
            return legacyJson;
        }
        return localJson;
    }

    private void ensureManagedVersionAvailableLocally(VersionEntry version) throws Exception {
        if (version == null) {
            return;
        }
        ensureVersionAvailableLocally(version.id());
    }

    private void ensureVersionAvailableLocally(String versionId) throws Exception {
        if (versionId == null || versionId.isBlank()) {
            return;
        }
        Path localVersionDir = gameDir.resolve("versions").resolve(versionId);
        if (Files.isDirectory(localVersionDir)) {
            return;
        }
        Path sourceVersionDir = appPaths.legacyDataDir().resolve("versions").resolve(versionId);
        if (!Files.isDirectory(sourceVersionDir)) {
            return;
        }
        copyDirectory(sourceVersionDir, localVersionDir);
        appendLog("[Limecraft] Imported version " + versionId + " into " + appPaths.storageModeLabel() + " storage");
    }

    private Path logsDirFor(VersionEntry version) {
        return instanceDirFor(version).resolve("logs");
    }

    private Path crashReportsDirFor(VersionEntry version) {
        return instanceDirFor(version).resolve("crash-reports");
    }

    private void openLogsFolder(VersionEntry version) {
        openDesktopFolder(logsDirFor(version), true, "Opened logs folder for " + version.id());
    }

    private void openCrashReportsFolder(VersionEntry version) {
        openDesktopFolder(crashReportsDirFor(version), true, "Opened crash reports folder for " + version.id());
    }

    private void openDesktopFolder(Path folder, boolean createIfMissing, String successStatus) {
        io.submit(() -> {
            try {
                if (createIfMissing) {
                    Files.createDirectories(folder);
                } else if (!Files.isDirectory(folder)) {
                    throw new IllegalStateException("Folder does not exist: " + folder);
                }
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("Desktop integration is not supported on this system.");
                }
                Desktop.getDesktop().open(folder.toFile());
                setStatus(successStatus, 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void createProfileSnapshot(VersionEntry version) {
        io.submit(() -> {
            try {
                Path snapshot = backupService.snapshotVersion(
                        version.id(),
                        gameDir.resolve("versions").resolve(version.id()),
                        instanceDirFor(version)
                );
                setStatus(snapshot == null
                        ? "Nothing to snapshot for " + version.id()
                        : "Created snapshot for " + version.id(),
                        0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void diagnoseLatestCrash(VersionEntry version) {
        io.submit(() -> {
            try {
                Platform.runLater(() -> {
                    try {
                        CrashReportAnalyzer.DiagnosisReport diagnosis = crashReportAnalyzer.analyze(instanceDirFor(version));
                        showDiagnosisDialog("Crash Diagnosis: " + version.id(), diagnosis);
                    } catch (Exception ex) {
                        fail(ex);
                    }
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void recordPlaySessionForCurrentLaunch() {
        String versionId = currentGameLaunchVersionId;
        long startedAt = currentGameLaunchStartedAtMillis;
        currentGameLaunchVersionId = null;
        currentGameLaunchStartedAtMillis = 0L;
        if (versionId == null || versionId.isBlank() || startedAt <= 0L) {
            return;
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        if (seconds <= 0L) {
            return;
        }
        try {
            profileMetadataStore.recordPlaySession(versionId, seconds);
            profileMetadataCache = profileMetadataStore.loadAll();
            Platform.runLater(() -> {
                if (versionsList != null) {
                    versionsList.refresh();
                }
                if (serverVersionsList != null) {
                    serverVersionsList.refresh();
                }
            });
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to record playtime: " + ex.getMessage());
        }
    }

    private void showVersionChangelog(VersionEntry version) {
        if (version == null || isManagedVersion(version)) {
            setStatus("Changelog is only available for vanilla versions.", 0);
            return;
        }

        io.submit(() -> {
            try {
                setStatus("Loading official changelog for " + version.id() + "...", 0.2);
                JsonObject index = http.getJson(JAVA_PATCH_NOTES_INDEX_URL);
                JsonObject entry = findPatchNoteEntry(index, version.id());
                if (entry == null) {
                    throw new IllegalStateException("No official Microsoft changelog entry found for " + version.id() + ".");
                }

                String contentPath = entry.has("contentPath") ? entry.get("contentPath").getAsString() : "";
                if (contentPath.isBlank()) {
                    throw new IllegalStateException("The changelog entry for " + version.id() + " is missing content.");
                }

                String contentUrl = JAVA_PATCH_NOTES_BASE_URL + contentPath;
                JsonObject full = http.getJson(contentUrl);
                String title = full.has("title") ? full.get("title").getAsString() : ("Minecraft " + version.id());
                String type = full.has("type") ? full.get("type").getAsString() : "unknown";
                String date = full.has("date") ? formatIsoDate(full.get("date").getAsString()) : "unknown date";
                String body = full.has("body") ? htmlToPlainText(full.get("body").getAsString()) : "";
                if (body.isBlank() && full.has("shortText")) {
                    body = htmlToPlainText(full.get("shortText").getAsString());
                }
                String finalBody = body.isBlank() ? "No changelog body provided." : body;

                Platform.runLater(() -> openChangelogDialog(version.id(), title, type, date, finalBody));
                setStatus("Opened official changelog for " + version.id(), 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private JsonObject findPatchNoteEntry(JsonObject index, String versionId) {
        if (index == null || !index.has("entries") || !index.get("entries").isJsonArray()) {
            return null;
        }
        JsonArray entries = index.getAsJsonArray("entries");
        String target = versionId == null ? "" : versionId.trim();
        String normalizedTarget = normalizeVersionToken(target);

        JsonObject prefixMatch = null;
        JsonObject titleMatch = null;

        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            String entryVersion = entry.has("version") ? entry.get("version").getAsString() : "";
            String entryTitle = entry.has("title") ? entry.get("title").getAsString() : "";

            if (entryVersion.equalsIgnoreCase(target)) {
                return entry;
            }
            if (normalizeVersionToken(entryVersion).equals(normalizedTarget)) {
                return entry;
            }
            if (entryVersion.toLowerCase(Locale.ROOT).startsWith(target.toLowerCase(Locale.ROOT) + "-") && prefixMatch == null) {
                prefixMatch = entry;
            }
            if (entryTitle.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT)) && titleMatch == null) {
                titleMatch = entry;
            }
        }
        return prefixMatch != null ? prefixMatch : titleMatch;
    }

    private String normalizeVersionToken(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String formatIsoDate(String value) {
        try {
            return OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private String htmlToPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("(?i)<p\\s*>", "")
                .replaceAll("(?i)</h[1-6]\\s*>", "\n")
                .replaceAll("(?i)<h[1-6]\\s*>", "\n")
                .replaceAll("(?i)<li\\s*>", "\n- ")
                .replaceAll("(?i)</li\\s*>", "")
                .replaceAll("(?i)</ul\\s*>", "\n");

        text = HTML_TAG_PATTERN.matcher(text).replaceAll("");
        text = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        text = decodeNumericHtmlEntities(text);
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String decodeNumericHtmlEntities(String value) {
        String decoded = value;
        Matcher dec = HTML_DEC_ENTITY_PATTERN.matcher(decoded);
        StringBuffer decOut = new StringBuffer();
        while (dec.find()) {
            String replacement = numericEntityToString(dec.group(1), 10);
            dec.appendReplacement(decOut, Matcher.quoteReplacement(replacement));
        }
        dec.appendTail(decOut);

        Matcher hex = HTML_HEX_ENTITY_PATTERN.matcher(decOut.toString());
        StringBuffer hexOut = new StringBuffer();
        while (hex.find()) {
            String replacement = numericEntityToString(hex.group(1), 16);
            hex.appendReplacement(hexOut, Matcher.quoteReplacement(replacement));
        }
        hex.appendTail(hexOut);
        return hexOut.toString();
    }

    private String numericEntityToString(String raw, int radix) {
        try {
            int codePoint = Integer.parseInt(raw, radix);
            return new String(Character.toChars(codePoint));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void openChangelogDialog(String versionId, String title, String type, String date, String body) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Changelog: " + versionId);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label meta = new Label("Type: " + type + " | Date: " + date);
        TextArea content = new TextArea(body);
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefSize(760, 520);

        VBox box = new VBox(10, meta, content);
        box.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(box);
        dialog.showAndWait();
    }

    private void setModeVisibility(VBox microsoftAuthBox, VBox offlineAuthBox, boolean microsoft) {
        microsoftAuthBox.setManaged(microsoft);
        microsoftAuthBox.setVisible(microsoft);
        offlineAuthBox.setManaged(!microsoft);
        offlineAuthBox.setVisible(!microsoft);
    }

    private void refreshVersions() {
        setStatus("Loading versions...", 0.05);
        io.submit(() -> {
            List<VersionEntry> custom = List.of();
            List<VersionEntry> cachedOfficial = List.of();
            try {
                custom = loadCustomVersions();
                cachedOfficial = installService.listCachedVersions();
                List<VersionEntry> initialVersions = mergeVersionLists(custom, cachedOfficial);
                int localVersionCount = custom.size();
                Map<String, ProfileMetadata> initialMetadataCache = profileMetadataStore.loadAll();
                int cachedOfficialCount = cachedOfficial.size();
                Platform.runLater(() -> {
                    profileMetadataCache = initialMetadataCache;
                    installedClientVersionCache = new LinkedHashMap<>();
                    allVersions = initialVersions;
                    applyVersionFilter();
                    applyServerVersionFilter();
                    if (cachedOfficialCount > 0) {
                        setStatus("Loaded " + cachedOfficialCount + " cached official + " + localVersionCount + " added version(s). Refreshing official versions...", 0.08);
                    } else {
                        setStatus("Loaded " + localVersionCount + " added version(s). Loading official Minecraft versions...", 0.08);
                    }
                });

                List<VersionEntry> official = installService.listVersions();
                int officialVersionCount = official.size();
                List<VersionEntry> combined = mergeVersionLists(custom, official);

                Platform.runLater(() -> {
                    installedClientVersionCache = new LinkedHashMap<>(installedClientVersionCache);
                    allVersions = combined;
                    applyVersionFilter();
                    applyServerVersionFilter();
                    setStatus("Loaded " + officialVersionCount + " official + " + localVersionCount + " added version(s)", 0);
                });
            } catch (Exception ex) {
                List<VersionEntry> fallbackVersions = mergeVersionLists(custom, cachedOfficial);
                Map<String, ProfileMetadata> fallbackMetadataCache = profileMetadataStore.loadAll();
                Platform.runLater(() -> {
                    profileMetadataCache = fallbackMetadataCache;
                    installedClientVersionCache = new LinkedHashMap<>(installedClientVersionCache);
                    allVersions = fallbackVersions;
                    applyVersionFilter();
                    applyServerVersionFilter();
                    appendLog("[Limecraft] Official version refresh failed: " + friendlyErrorMessage(ex));
                    if (!fallbackVersions.isEmpty()) {
                        setStatus("Loaded cached/local versions only. Official Minecraft version refresh failed.", 0);
                    } else {
                        setStatus("Official Minecraft version refresh failed.", 0);
                    }
                });
            }
        });
    }

    private List<VersionEntry> mergeVersionLists(List<VersionEntry> preferred, List<VersionEntry> secondary) {
        List<VersionEntry> merged = new ArrayList<>();
        if (preferred != null) {
            merged.addAll(preferred);
        }
        if (secondary != null) {
            for (VersionEntry version : secondary) {
                boolean duplicate = merged.stream().anyMatch(existing -> existing.id().equalsIgnoreCase(version.id()));
                if (!duplicate) {
                    merged.add(version);
                }
            }
        }
        return merged;
    }

    private void importLegacyManagedVersions() {
        if (legacyManagedVersionImportAttempted) {
            return;
        }
        legacyManagedVersionImportAttempted = true;

        Path legacyDir = appPaths.legacyDataDir();
        if (legacyDir == null || legacyDir.equals(gameDir)) {
            return;
        }

        Path legacyVersionsDir = legacyDir.resolve("versions");
        if (!Files.isDirectory(legacyVersionsDir)) {
            return;
        }

        Path targetVersionsDir = gameDir.resolve("versions");
        int importedCount = 0;
        try {
            Files.createDirectories(targetVersionsDir);
            try (Stream<Path> stream = Files.list(legacyVersionsDir)) {
                for (Path sourceDir : stream.filter(Files::isDirectory).toList()) {
                    String id = sourceDir.getFileName().toString();
                    Path sourceJson = sourceDir.resolve(id + ".json");
                    if (!Files.exists(sourceJson)) {
                        continue;
                    }

                    JsonObject meta;
                    try {
                        meta = JsonParser.parseString(Files.readString(sourceJson)).getAsJsonObject();
                    } catch (Exception ignored) {
                        continue;
                    }

                    String type = meta.has("type") ? meta.get("type").getAsString() : "";
                    String loader = detectLoaderFamilyFromMetadata(meta, type);
                    if ("vanilla".equalsIgnoreCase(loader)) {
                        continue;
                    }

                    Path targetDir = targetVersionsDir.resolve(id);
                    if (Files.isDirectory(targetDir)) {
                        continue;
                    }

                    copyDirectory(sourceDir, targetDir);
                    importedCount++;
                }
            }
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to import local versions from " + legacyDir + ": " + ex.getMessage());
            return;
        }

        if (importedCount > 0) {
            appendLog("[Limecraft] Imported " + importedCount + " local version(s) from " + legacyDir);
        }
    }

    private void importLegacyInstanceFolders() {
        if (legacyInstanceImportAttempted) {
            return;
        }
        legacyInstanceImportAttempted = true;

        Path legacyDir = appPaths.legacyDataDir();
        if (legacyDir == null || legacyDir.equals(gameDir)) {
            return;
        }

        Path legacyInstancesDir = legacyDir.resolve("instances");
        if (!Files.isDirectory(legacyInstancesDir)) {
            return;
        }

        Path targetInstancesDir = gameDir.resolve("instances");
        int importedCount = 0;
        try {
            Files.createDirectories(targetInstancesDir);
            try (Stream<Path> stream = Files.list(legacyInstancesDir)) {
                for (Path sourceDir : stream.filter(Files::isDirectory).toList()) {
                    Path targetDir = targetInstancesDir.resolve(sourceDir.getFileName().toString());
                    if (Files.exists(targetDir)) {
                        continue;
                    }
                    copyDirectory(sourceDir, targetDir);
                    importedCount++;
                }
            }
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to import legacy instance folders from " + legacyDir + ": " + ex.getMessage());
            return;
        }

        if (importedCount > 0) {
            appendLog("[Limecraft] Imported " + importedCount + " legacy instance folder(s) into " + appPaths.storageModeLabel() + " storage");
        }
    }

    private List<VersionEntry> loadCustomVersions() {
        List<VersionEntry> custom = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        loadCustomVersionsFrom(gameDir.resolve("versions"), custom, seenIds, "local");
        Path legacyVersionsDir = appPaths.legacyDataDir().resolve("versions");
        if (!legacyVersionsDir.equals(gameDir.resolve("versions"))) {
            loadCustomVersionsFrom(legacyVersionsDir, custom, seenIds, "legacy");
        }
        custom.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        return custom;
    }

    private void loadCustomVersionsFrom(Path versionsDir, List<VersionEntry> custom, Set<String> seenIds, String sourceLabel) {
        if (!Files.isDirectory(versionsDir)) {
            return;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String id = dir.getFileName().toString();
                String normalizedId = id.toLowerCase(Locale.ROOT);
                if (seenIds.contains(normalizedId)) {
                    return;
                }
                Path json = dir.resolve(id + ".json");
                if (!Files.exists(json)) {
                    return;
                }
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    String type = meta.has("type") ? meta.get("type").getAsString() : "";
                    String detectedLoader = detectLoaderFamilyFromMetadata(meta, type);
                    if ("vanilla".equalsIgnoreCase(detectedLoader)) {
                        return;
                    }
                    String entryType = "custom".equalsIgnoreCase(type) ? "custom" : detectedLoader;
                    String releaseTime = meta.has("releaseTime")
                            ? meta.get("releaseTime").getAsString()
                            : Instant.now().toString();
                    seenIds.add(normalizedId);
                    custom.add(new VersionEntry(id, entryType, "", releaseTime, detectProfileSideFromMetadata(meta)));
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to load " + sourceLabel + " versions from " + versionsDir + ": " + ex.getMessage());
        }
    }

    private boolean isFabricMetadata(JsonObject meta) {
        if (meta == null) {
            return false;
        }
        return "fabric".equalsIgnoreCase(detectLoaderFamilyFromMetadata(meta, meta.has("type") ? meta.get("type").getAsString() : ""));
    }

    private String detectLoaderFamilyFromMetadata(JsonObject meta, String fallbackType) {
        if (meta == null) {
            return fallbackType == null || fallbackType.isBlank() ? "vanilla" : fallbackType.toLowerCase(Locale.ROOT);
        }
        if (meta.has(ModloaderInstallService.META_LOADER)) {
            String explicitLoader = meta.get(ModloaderInstallService.META_LOADER).getAsString();
            if (!explicitLoader.isBlank()) {
                return explicitLoader.toLowerCase(Locale.ROOT);
            }
        }
        String explicitType = meta.has("type") ? meta.get("type").getAsString() : "";
        if ("fabric".equalsIgnoreCase(explicitType)
                || "quilt".equalsIgnoreCase(explicitType)
                || "forge".equalsIgnoreCase(explicitType)
                || "neoforge".equalsIgnoreCase(explicitType)) {
            return explicitType.toLowerCase(Locale.ROOT);
        }
        if (hasLibraryPrefix(meta, "net.fabricmc:fabric-loader")) {
            return "fabric";
        }
        if (hasLibraryPrefix(meta, "org.quiltmc:quilt-loader")) {
            return "quilt";
        }
        if (hasLibraryPrefix(meta, "net.neoforged:neoforge")) {
            return "neoforge";
        }
        if (hasLibraryPrefix(meta, "net.minecraftforge:forge")
                || hasLibraryPrefix(meta, "net.minecraftforge:fmlloader")
                || hasLibraryPrefix(meta, "cpw.mods:modlauncher")) {
            return "forge";
        }
        if ("custom".equalsIgnoreCase(explicitType)) {
            return "custom";
        }
        String lowerFallback = fallbackType == null ? "" : fallbackType.trim().toLowerCase(Locale.ROOT);
        if ("custom".equals(lowerFallback)) {
            return "custom";
        }
        return "vanilla";
    }

    private String detectProfileSideFromMetadata(JsonObject meta) {
        if (meta == null || !meta.has(ModloaderInstallService.META_SIDE)) {
            return "both";
        }
        String side = meta.get(ModloaderInstallService.META_SIDE).getAsString();
        if ("client".equalsIgnoreCase(side)) {
            return "client";
        }
        if ("server".equalsIgnoreCase(side)) {
            return "server";
        }
        return "both";
    }

    private void applyServerVersionFilter() {
        if (serverVersionsList == null) {
            return;
        }
        VersionEntry currentSelection = serverVersionsList.getSelectionModel().getSelectedItem();
        String preferredId = currentSelection != null ? currentSelection.id() : null;
        String query = serverSearchField == null || serverSearchField.getText() == null
                ? ""
                : serverSearchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean includeExperimental = serverExperimentalToggle == null || serverExperimentalToggle.isSelected();

        List<VersionEntry> filtered = new ArrayList<>();
        for (VersionEntry v : allVersions) {
            if (!v.supportsServer()) {
                continue;
            }
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
            serverVersionsList.getSelectionModel().select(preferredIdx >= 0 ? preferredIdx : 0);
        } else if (serverSelectedVersionLabel != null) {
            serverSelectedVersionLabel.setText("Selected: none");
        }
        updateServerModBrowserButtonState();
    }

    private boolean isServerInstalled(VersionEntry version) {
        Path serverDir = serverDirFor(version);
        return Files.exists(serverDir.resolve("server.jar"))
                || Files.exists(serverDir.resolve("run.bat"))
                || Files.exists(serverDir.resolve("run.sh"));
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
                Path serverDir = serverDirFor(selected);
                ServerProfileSettings settings = readServerSettingsFromUi();
                serverProfileStore.save(serverDir, settings);
                Path serverJar = Files.exists(serverDir.resolve("run.bat")) || Files.exists(serverDir.resolve("run.sh"))
                        ? null
                        : ensureServerInstalled(selected, serverDir, settings.javaPath());
                writeServerFiles(serverDir, settings);

                List<String> args = buildServerLaunchCommand(serverDir, serverJar, settings);

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(serverDir.toFile());
                pb.redirectErrorStream(true);
                applyServerJavaEnvironment(pb, settings.javaPath());
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

    private Path ensureServerInstalled(VersionEntry version, Path serverDir, String javaExecutable) throws Exception {
        Files.createDirectories(serverDir);
        Path serverJar = serverDir.resolve("server.jar");
        if (Files.exists(serverJar)) {
            return serverJar;
        }

        ensureManagedVersionAvailableLocally(version);
        JsonObject localMeta = loadVersionMetaQuiet(version.id());
        if ((version.url() == null || version.url().isBlank()) && localMeta != null) {
            String side = detectProfileSideFromMetadata(localMeta);
            String loader = detectLoaderFamilyFromMetadata(localMeta, version.type());
            if ("server".equalsIgnoreCase(side)
                    && !"vanilla".equalsIgnoreCase(loader)
                    && !"custom".equalsIgnoreCase(loader)) {
                appendServerLog("[Limecraft] Reinstalling " + loader + " server files for " + version.id() + "...");
                modloaderInstallService.reinstallServerFromMetadata(version.id(), localMeta, javaExecutable, this::setStatus);
                appendServerLog("[Limecraft] Server install complete");
                return serverJar;
            }
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

    private void writeServerFiles(Path serverDir, ServerProfileSettings settings) throws Exception {
        Files.createDirectories(serverDir);
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true" + System.lineSeparator());
        serverProfileStore.mergeIntoServerProperties(serverDir, settings);
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

    private Path serverDirFor(VersionEntry version) {
        return gameDir.resolve("servers").resolve(version.id());
    }

    private void selectServerVersionById(String id) {
        if (id == null || id.isBlank() || serverVersionsList == null) {
            return;
        }
        for (VersionEntry v : serverVersionsList.getItems()) {
            if (id.equalsIgnoreCase(v.id())) {
                serverVersionsList.getSelectionModel().select(v);
                serverVersionsList.scrollTo(v);
                return;
            }
        }
    }

    private void loadServerSettingsForSelection(VersionEntry version) {
        if (version == null) {
            return;
        }
        io.submit(() -> {
            try {
                ServerProfileSettings settings = serverProfileStore.load(serverDirFor(version), javaPathField == null ? "java" : javaPathField.getText());
                Platform.runLater(() -> applyServerSettings(settings));
            } catch (Exception ex) {
                appendServerLog("[Limecraft] Failed to load saved server profile: " + ex.getMessage());
            }
        });
    }

    private void applyServerSettings(ServerProfileSettings settings) {
        loadingServerSettings = true;
        try {
            serverJavaPathField.setText(settings.javaPath());
            serverRamField.setText(settings.xmx());
            serverPortField.setText(String.valueOf(settings.port()));
            serverMotdField.setText(settings.motd());
            serverMaxPlayersField.setText(String.valueOf(settings.maxPlayers()));
            serverOnlineModeToggle.setSelected(settings.onlineMode());
            serverPvpToggle.setSelected(settings.pvp());
            serverCommandBlocksToggle.setSelected(settings.commandBlocks());
            serverNoguiToggle.setSelected(settings.nogui());
        } finally {
            loadingServerSettings = false;
        }
    }

    private void persistSelectedServerSettings() {
        if (loadingServerSettings || serverVersionsList == null) {
            return;
        }
        VersionEntry selected = serverVersionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        try {
            serverProfileStore.save(serverDirFor(selected), readServerSettingsFromUi());
        } catch (Exception ex) {
            appendServerLog("[Limecraft] Failed to save server profile: " + ex.getMessage());
        }
    }

    private ServerProfileSettings readServerSettingsFromUi() {
        String javaPath = serverJavaPathField.getText() == null || serverJavaPathField.getText().trim().isBlank()
                ? "java"
                : serverJavaPathField.getText().trim();
        String xmx = serverRamField.getText() == null || serverRamField.getText().trim().isBlank()
                ? "2G"
                : serverRamField.getText().trim();
        return new ServerProfileSettings(
                javaPath,
                xmx,
                sanitizeInt(serverPortField.getText(), 25565),
                sanitizeText(serverMotdField.getText(), "A Limecraft Server"),
                sanitizeInt(serverMaxPlayersField.getText(), 20),
                serverOnlineModeToggle.isSelected(),
                serverPvpToggle.isSelected(),
                serverCommandBlocksToggle.isSelected(),
                serverNoguiToggle.isSelected()
        );
    }

    private List<String> buildServerLaunchCommand(Path serverDir, Path serverJar, ServerProfileSettings settings) {
        Path runBat = serverDir.resolve("run.bat");
        if (Files.exists(runBat)) {
            return List.of("cmd.exe", "/c", runBat.getFileName().toString());
        }
        Path runSh = serverDir.resolve("run.sh");
        if (Files.exists(runSh)) {
            return List.of("bash", runSh.getFileName().toString());
        }

        List<String> args = new ArrayList<>();
        args.add(settings.javaPath());
        args.add("-Xmx" + settings.xmx());
        args.add("-jar");
        args.add(serverJar == null ? "server.jar" : serverJar.getFileName().toString());
        if (settings.nogui()) {
            args.add("nogui");
        }
        return args;
    }

    private void applyServerJavaEnvironment(ProcessBuilder pb, String javaPath) {
        if (javaPath == null || javaPath.isBlank()) {
            return;
        }
        Path executable = Path.of(javaPath.trim());
        Path binDir = executable.getParent();
        if (binDir == null) {
            return;
        }
        Path javaHome = binDir.getParent();
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome.toString());
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
        closeServerCommandWriter();
        destroyProcessTree(process);
        if (process.isAlive()) {
            appendServerLog("[Limecraft] Server process is still running after kill attempt.");
        }
    }

    private void closeServerCommandWriter() {
        BufferedWriter writer = serverCommandWriter;
        serverCommandWriter = null;
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (Exception ignored) {
        }
    }

    private void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants = handle.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
        if (!running) {
            updateServerModBrowserButtonState();
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
        if (microsoftSignInInProgress) {
            setStatus("Microsoft sign-in is already in progress...", 0);
            return;
        }
        microsoftSignInInProgress = true;
        Platform.runLater(() -> signInButton.setDisable(true));
        updateAccountIndicator();

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

                SavedMicrosoftAccount savedAccount = accountStore.saveAccount(account);
                signedInAccount = account;
                selectedAccountId = savedAccount.profileId();
                savedMicrosoftRefreshToken = "";
                saveSettings();
                Platform.runLater(() -> {
                    refreshSavedAccountsBox();
                    selectSavedAccountById(savedAccount.profileId());
                    accountLabel.setText("Signed in as " + account.username());
                    updateAccountIndicator();
                    setStatus("Signed in successfully", 0);
                });
            } catch (Exception ex) {
                fail(ex);
            } finally {
                microsoftSignInInProgress = false;
                Platform.runLater(() -> {
                    signInButton.setDisable(false);
                    updateAccountIndicator();
                });
            }
        });
    }

    private void restoreSavedMicrosoftSession() {
        SavedMicrosoftAccount selected = selectedSavedAccount();
        if (selected != null) {
            restoreSavedMicrosoftSession(selected, true);
            return;
        }
        String refreshToken = normalizeToken(savedMicrosoftRefreshToken);
        if (refreshToken.isBlank()) {
            return;
        }

        appendLog("[Limecraft] Restoring saved Microsoft sign-in...");
        setStatus("Restoring Microsoft session...", 0.08);
        io.submit(() -> {
            try {
                MicrosoftAuthService auth = new MicrosoftAuthService(http, MICROSOFT_CLIENT_ID);
                MinecraftAccount account = auth.signInWithRefreshToken(refreshToken);
                if (!auth.hasGameOwnership(account.accessToken())) {
                    throw new IllegalStateException("Saved Microsoft account does not own Minecraft Java Edition.");
                }

                SavedMicrosoftAccount savedAccount = accountStore.saveAccount(account);
                signedInAccount = account;
                selectedAccountId = savedAccount.profileId();
                savedMicrosoftRefreshToken = "";
                saveSettings();
                Platform.runLater(() -> {
                    refreshSavedAccountsBox();
                    selectSavedAccountById(savedAccount.profileId());
                    accountLabel.setText("Signed in as " + account.username());
                    updateAccountIndicator();
                    setStatus("Signed in from saved session", 0);
                });
            } catch (Exception ex) {
                signedInAccount = null;
                savedMicrosoftRefreshToken = "";
                saveSettings();
                Platform.runLater(() -> {
                    accountLabel.setText("Not signed in");
                    updateAccountIndicator();
                    appendLog("[Limecraft] Saved Microsoft session expired: " + ex.getMessage());
                    if (MODE_MICROSOFT.equals(accountModeBox.getValue())) {
                        setStatus("Microsoft session expired, sign in again.", 0);
                    }
                });
            }
        });
    }

    private void restoreSelectedAccountSession() {
        SavedMicrosoftAccount account = savedAccountsBox == null ? null : savedAccountsBox.getValue();
        if (account == null) {
            setStatus("Select a saved Microsoft account first.", 0);
            return;
        }
        restoreSavedMicrosoftSession(account, false);
    }

    private void restoreSavedMicrosoftSession(SavedMicrosoftAccount savedAccount, boolean automatic) {
        if (savedAccount == null) {
            return;
        }
        if (microsoftSignInInProgress) {
            if (!automatic) {
                setStatus("Microsoft sign-in is already in progress...", 0);
            }
            return;
        }
        microsoftSignInInProgress = true;
        Platform.runLater(() -> {
            signInButton.setDisable(true);
            useSavedAccountButton.setDisable(true);
            updateAccountIndicator();
        });

        String profileId = savedAccount.profileId();
        appendLog("[Limecraft] Restoring saved Microsoft sign-in for " + savedAccount.username() + "...");
        setStatus("Restoring Microsoft session for " + savedAccount.username() + "...", 0.08);
        io.submit(() -> {
            try {
                String refreshToken = normalizeToken(accountStore.loadRefreshToken(profileId));
                if (refreshToken.isBlank()) {
                    throw new IllegalStateException("No refresh token was stored for " + savedAccount.username() + ".");
                }
                MicrosoftAuthService auth = new MicrosoftAuthService(http, MICROSOFT_CLIENT_ID);
                MinecraftAccount account = auth.signInWithRefreshToken(refreshToken);
                if (!auth.hasGameOwnership(account.accessToken())) {
                    throw new IllegalStateException("Saved Microsoft account does not own Minecraft Java Edition.");
                }

                SavedMicrosoftAccount updated = accountStore.saveAccount(account);
                signedInAccount = account;
                selectedAccountId = updated.profileId();
                saveSettings();
                Platform.runLater(() -> {
                    refreshSavedAccountsBox();
                    selectSavedAccountById(updated.profileId());
                    accountLabel.setText("Signed in as " + account.username());
                    updateAccountIndicator();
                    setStatus("Signed in from saved session", 0);
                });
            } catch (Exception ex) {
                signedInAccount = null;
                Platform.runLater(() -> {
                    accountLabel.setText("Not signed in");
                    updateAccountIndicator();
                    appendLog("[Limecraft] Saved Microsoft session expired: " + ex.getMessage());
                    if (MODE_MICROSOFT.equals(accountModeBox.getValue())) {
                        setStatus("Microsoft session expired, sign in again.", 0);
                    }
                });
            } finally {
                microsoftSignInInProgress = false;
                Platform.runLater(() -> {
                    signInButton.setDisable(false);
                    useSavedAccountButton.setDisable(false);
                    updateAccountIndicator();
                });
            }
        });
    }

    private void signOutCurrentAccount() {
        signedInAccount = null;
        accountLabel.setText("Not signed in");
        updateAccountIndicator();
        setStatus("Signed out of the current Microsoft session.", 0);
    }

    private void removeSelectedAccount() {
        SavedMicrosoftAccount selected = savedAccountsBox == null ? null : savedAccountsBox.getValue();
        if (selected == null) {
            setStatus("Select a saved Microsoft account first.", 0);
            return;
        }
        io.submit(() -> {
            try {
                accountStore.removeAccount(selected.profileId());
                if (signedInAccount != null && selected.profileId().equalsIgnoreCase(accountStore.profileIdFor(signedInAccount))) {
                    signedInAccount = null;
                }
                if (selected.profileId().equalsIgnoreCase(selectedAccountId)) {
                    selectedAccountId = "";
                }
                saveSettings();
                Platform.runLater(() -> {
                    refreshSavedAccountsBox();
                    accountLabel.setText(signedInAccount == null ? "Not signed in" : "Signed in as " + signedInAccount.username());
                    updateAccountIndicator();
                    setStatus("Removed saved account " + selected.username(), 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void refreshSavedAccountsBox() {
        if (savedAccountsBox == null) {
            return;
        }
        try {
            List<SavedMicrosoftAccount> accounts = accountStore.loadAccounts();
            savedAccountsBox.setItems(FXCollections.observableArrayList(accounts));
            if (!selectedAccountId.isBlank()) {
                selectSavedAccountById(selectedAccountId);
            } else if (!accounts.isEmpty()) {
                savedAccountsBox.getSelectionModel().selectFirst();
            }
            boolean hasAccounts = !accounts.isEmpty();
            useSavedAccountButton.setDisable(!hasAccounts);
            removeAccountButton.setDisable(!hasAccounts);
            updateAccountIndicator();
        } catch (Exception ex) {
            appendLog("[Limecraft] Failed to load saved accounts: " + ex.getMessage());
        }
    }

    private SavedMicrosoftAccount selectedSavedAccount() {
        if (savedAccountsBox == null) {
            return null;
        }
        SavedMicrosoftAccount selected = savedAccountsBox.getValue();
        if (selected != null) {
            selectedAccountId = selected.profileId();
            return selected;
        }
        if (selectedAccountId == null || selectedAccountId.isBlank()) {
            return null;
        }
        for (SavedMicrosoftAccount account : savedAccountsBox.getItems()) {
            if (selectedAccountId.equalsIgnoreCase(account.profileId())) {
                return account;
            }
        }
        return null;
    }

    private void selectSavedAccountById(String profileId) {
        if (savedAccountsBox == null || profileId == null || profileId.isBlank()) {
            return;
        }
        for (SavedMicrosoftAccount account : savedAccountsBox.getItems()) {
            if (profileId.equalsIgnoreCase(account.profileId())) {
                savedAccountsBox.getSelectionModel().select(account);
                return;
            }
        }
    }

    private String normalizeToken(String token) {
        return token == null ? "" : token.trim();
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
                ensureVersionAvailableLocally(selected.id());
                Path versionDir = gameDir.resolve("versions").resolve(selected.id());
                Path versionJson = findVersionJson(selected.id());

                if (!Files.exists(versionJson)) {
                    if (selected.url() == null || selected.url().isBlank()) {
                        throw new IllegalStateException("Version metadata is missing for " + selected.id());
                    }
                    setStatus("Installing " + selected.id() + " before launch...", 0.15);
                    installService.installVersion(selected, this::setStatus);
                    setStatus("Installed " + selected.id(), 0.8);
                }

                JsonObject meta = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
                if (!versionJarExists(selected.id()) && !meta.has("inheritsFrom")) {
                    if (selected.url() == null || selected.url().isBlank()) {
                        throw new IllegalStateException("Missing client jar for " + selected.id());
                    }
                    setStatus("Installing missing jar for " + selected.id() + "...", 0.25);
                    installService.installVersion(selected, this::setStatus);
                    meta = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
                }
                if (meta.has("inheritsFrom")) {
                    installService.installMetadataDependenciesIfNeeded(meta, this::setStatus);
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

                ensureInstanceAvailableLocally(selected);
                InstanceSettings instanceSettings = loadInstanceSettings(selected);
                String javaPath = instanceSettings.javaPath().isBlank()
                        ? javaPathField.getText().trim()
                        : instanceSettings.javaPath().trim();
                String xmx = instanceSettings.xmx().isBlank()
                        ? ramField.getText().trim()
                        : instanceSettings.xmx().trim();

                setStatus("Launching " + selected.id() + "...", 0.9);
                Process process = launchService.launch(
                        selected.id(),
                        meta,
                        accountToUse,
                        offlineUsername,
                        javaPath,
                        xmx,
                        includeLimecraftSuffix,
                        instanceSettings.hideCustomSuffix()
                );
                currentGameProcess = process;
                currentGameLaunchStartedAtMillis = System.currentTimeMillis();
                currentGameLaunchVersionId = selected.id();
                Platform.runLater(() -> {
                    installedClientVersionCache.remove(selected.id());
                    versionsList.refresh();
                    setGameRunning(true);
                    recordRecentLaunch(selected.id());
                });
                process.onExit().thenRun(() -> {
                    if (currentGameProcess == process) {
                        currentGameProcess = null;
                        recordPlaySessionForCurrentLaunch();
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

    private void repairSelectedVersion() {
        VersionEntry selected = versionsList == null ? null : versionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a version to repair first.", 0);
            return;
        }
        io.submit(() -> {
            try {
                setStatus("Repairing " + selected.id() + "...", 0.2);
                installService.invalidateDependencyCache(selected.id());
                if (selected.url() != null && !selected.url().isBlank()) {
                    installService.installVersion(selected, this::setStatus);
                } else {
                    ensureManagedVersionAvailableLocally(selected);
                    JsonObject meta = loadVersionMetaQuiet(selected.id());
                    if (meta == null) {
                        throw new IllegalStateException("No local metadata exists for " + selected.id() + ".");
                    }
                    installService.installMetadataDependencies(meta, this::setStatus);
                    ensureInheritedDependenciesInstalled(meta);
                }
                Platform.runLater(() -> {
                    installedClientVersionCache.remove(selected.id());
                    versionsList.refresh();
                    setStatus("Repaired " + selected.id(), 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void repairSelectedServer() {
        VersionEntry selected = serverVersionsList == null ? null : serverVersionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a server version to repair first.", 0);
            return;
        }
        io.submit(() -> {
            try {
                Path serverDir = serverDirFor(selected);
                Files.createDirectories(serverDir);
                Path serverJar = serverDir.resolve("server.jar");
                Files.deleteIfExists(serverJar);
                ServerProfileSettings settings = readServerSettingsFromUi();
                ensureServerInstalled(selected, serverDir, settings.javaPath());
                serverProfileStore.save(serverDir, settings);
                writeServerFiles(serverDir, settings);
                Platform.runLater(() -> {
                    serverVersionsList.refresh();
                    setStatus("Repaired server files for " + selected.id(), 0);
                });
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void openModBrowser(String initialSide) {
        String side = initialSide == null || initialSide.isBlank() ? "client" : initialSide.toLowerCase(Locale.ROOT);
        ModBrowserContext context = resolveSelectedModBrowserContext(side);
        if (context == null) {
            setStatus("Browse Mods is only available for Fabric, Quilt, Forge, or NeoForge profiles.", 0);
            return;
        }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Browse Mods");

        TextField queryField = new TextField();
        queryField.setPromptText("Search compatible Modrinth mods...");
        Label scopeLabel = new Label(
                "Target: " + context.target().id()
                        + " | Minecraft " + context.gameVersion()
                        + " | Loader: " + LoaderFamily.fromId(context.loader()).displayName()
        );
        scopeLabel.getStyleClass().add("selected-version");

        Label resultsStatusLabel = new Label("Loading compatible mods...");
        resultsStatusLabel.getStyleClass().add("selected-version");
        ListView<ModrinthService.Project> results = new ListView<>();
        results.getStyleClass().add("mod-browser-results");
        results.setPlaceholder(new Label("Compatible Modrinth results will appear here."));
        results.setCellFactory(list -> new ListCell<>() {
            private final Label title = new Label();
            private final Label meta = new Label();
            private final Label description = new Label();
            private final Label tags = new Label();
            private final VBox content = new VBox(4, title, meta, description, tags);

            {
                title.getStyleClass().add("mod-project-title");
                meta.getStyleClass().add("mod-project-meta");
                description.getStyleClass().add("mod-project-description");
                tags.getStyleClass().add("mod-project-tags");
                title.setWrapText(true);
                meta.setWrapText(true);
                description.setWrapText(true);
                tags.setWrapText(true);
                title.maxWidthProperty().bind(widthProperty().subtract(30));
                meta.maxWidthProperty().bind(widthProperty().subtract(30));
                description.maxWidthProperty().bind(widthProperty().subtract(30));
                tags.maxWidthProperty().bind(widthProperty().subtract(30));
            }

            @Override
            protected void updateItem(ModrinthService.Project item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                title.setText(item.title());
                meta.setText(formatModProjectListMeta(item));
                description.setText(trimPreviewText(item.description(), 170));
                tags.setText(formatModProjectCategoryLine(item.categories()));
                setText(null);
                setGraphic(content);
            }
        });

        WebView preview = new WebView();
        preview.setContextMenuEnabled(false);
        preview.getEngine().loadContent(buildModProjectPreviewHtml(
                context,
                null,
                null,
                "Search Modrinth and select a compatible mod to preview it here."
        ));
        Label previewStatusLabel = new Label("No mod selected");
        previewStatusLabel.getStyleClass().add("selected-version");
        Label installVersionStatusLabel = new Label("Select a mod to load supported Minecraft and mod versions.");
        installVersionStatusLabel.getStyleClass().add("selected-version");

        CheckBox showAllVersionsToggle = new CheckBox("Show All Versions");
        ComboBox<String> supportedMinecraftBox = new ComboBox<>();
        supportedMinecraftBox.setPromptText("Supported Minecraft version");
        supportedMinecraftBox.setMaxWidth(Double.MAX_VALUE);
        supportedMinecraftBox.setDisable(true);
        ComboBox<ModrinthService.Version> modVersionBox = new ComboBox<>();
        modVersionBox.setPromptText("Compatible mod version");
        modVersionBox.setMaxWidth(Double.MAX_VALUE);
        modVersionBox.setDisable(true);
        modVersionBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ModrinthService.Version item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatModVersionLabel(item));
            }
        });
        modVersionBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ModrinthService.Version item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatModVersionLabel(item));
            }
        });

        Button installButton = new Button("Install Selected");
        installButton.getStyleClass().add("launch-button");
        installButton.setDisable(true);

        Button openFolderButton = new Button("Open Mods Folder");
        openFolderButton.setOnAction(e -> openDesktopFolder(
                modsDirFor(context.target(), context.side()),
                true,
                "Opened mods folder for " + context.target().id()
        ));

        Button openPageButton = new Button("Open On Modrinth");
        openPageButton.setDisable(true);

        final long[] searchRequestId = new long[] { 0L };
        final long[] detailRequestId = new long[] { 0L };
        @SuppressWarnings("unchecked")
        final List<ModrinthService.Version>[] projectVersionsRef = new List[] { List.of() };
        final Runnable[] refreshInstallSelection = new Runnable[1];
        final Runnable[] refreshSupportedMinecraftVersions = new Runnable[1];

        refreshInstallSelection[0] = () -> {
            ModrinthService.Project project = results.getSelectionModel().getSelectedItem();
            String selectedMinecraft = supportedMinecraftBox.getValue();
            String selectedVersionId = modVersionBox.getValue() == null ? "" : modVersionBox.getValue().id();
            boolean showAllVersions = showAllVersionsToggle.isSelected();

            modVersionBox.getItems().clear();
            modVersionBox.getSelectionModel().clearSelection();
            modVersionBox.setDisable(true);
            installButton.setDisable(true);

            if (project == null) {
                installVersionStatusLabel.setText("Select a mod to load compatible versions.");
                return;
            }
            if (selectedMinecraft == null || selectedMinecraft.isBlank()) {
                installVersionStatusLabel.setText(showAllVersions
                        ? "No supported Minecraft versions were found for this mod."
                        : "No supported release Minecraft versions were found. Turn on Show All Versions.");
                return;
            }

            List<ModrinthService.Version> compatibleVersions = compatibleModVersions(
                    projectVersionsRef[0],
                    selectedMinecraft,
                    showAllVersions
            );
            if (compatibleVersions.isEmpty()) {
                installVersionStatusLabel.setText(showAllVersions
                        ? ("No downloadable mod versions were found for Minecraft " + selectedMinecraft + ".")
                        : ("No release mod versions were found for Minecraft " + selectedMinecraft + ". Turn on Show All Versions."));
                return;
            }

            modVersionBox.setDisable(false);
            modVersionBox.setItems(FXCollections.observableArrayList(compatibleVersions));
            for (ModrinthService.Version version : compatibleVersions) {
                if (version.id().equalsIgnoreCase(selectedVersionId)) {
                    modVersionBox.getSelectionModel().select(version);
                    break;
                }
            }
            if (modVersionBox.getValue() == null) {
                modVersionBox.getSelectionModel().selectFirst();
            }

            ModrinthService.Version selectedVersion = modVersionBox.getValue();
            if (selectedVersion == null) {
                installVersionStatusLabel.setText("Select a compatible mod version.");
                return;
            }

            boolean matchesProfileVersion = context.gameVersion().equalsIgnoreCase(selectedMinecraft);
            installButton.setDisable(!matchesProfileVersion);
            if (!matchesProfileVersion) {
                installVersionStatusLabel.setText("Selected profile targets Minecraft " + context.gameVersion()
                        + ". Switch back to that version to install.");
                return;
            }

            installVersionStatusLabel.setText("Ready to install " + formatModVersionShortLabel(selectedVersion)
                    + " for Minecraft " + selectedMinecraft + ".");
        };

        refreshSupportedMinecraftVersions[0] = () -> {
            ModrinthService.Project project = results.getSelectionModel().getSelectedItem();
            String previousMinecraft = supportedMinecraftBox.getValue();
            boolean showAllVersions = showAllVersionsToggle.isSelected();

            supportedMinecraftBox.getItems().clear();
            supportedMinecraftBox.getSelectionModel().clearSelection();
            supportedMinecraftBox.setDisable(true);

            if (project == null) {
                installVersionStatusLabel.setText("Select a mod to load compatible versions.");
                refreshInstallSelection[0].run();
                return;
            }

            List<String> supportedMinecraftVersions = supportedMinecraftVersionsForProject(projectVersionsRef[0], showAllVersions);
            if (supportedMinecraftVersions.isEmpty()) {
                installVersionStatusLabel.setText(showAllVersions
                        ? "This mod does not expose any supported Minecraft versions."
                        : "No supported release Minecraft versions were found. Turn on Show All Versions.");
                refreshInstallSelection[0].run();
                return;
            }

            supportedMinecraftBox.setDisable(false);
            supportedMinecraftBox.setItems(FXCollections.observableArrayList(supportedMinecraftVersions));
            if (previousMinecraft != null && supportedMinecraftVersions.contains(previousMinecraft)) {
                supportedMinecraftBox.getSelectionModel().select(previousMinecraft);
            } else if (supportedMinecraftVersions.contains(context.gameVersion())) {
                supportedMinecraftBox.getSelectionModel().select(context.gameVersion());
            } else {
                supportedMinecraftBox.getSelectionModel().selectFirst();
            }
            refreshInstallSelection[0].run();
        };

        Runnable performSearch = () -> {
            String query = queryField.getText() == null ? "" : queryField.getText().trim();
            long requestId = ++searchRequestId[0];
            results.setDisable(true);
            results.getItems().clear();
            results.getSelectionModel().clearSelection();
            projectVersionsRef[0] = List.of();
            installButton.setDisable(true);
            openPageButton.setDisable(true);
            supportedMinecraftBox.getItems().clear();
            supportedMinecraftBox.getSelectionModel().clearSelection();
            supportedMinecraftBox.setDisable(true);
            modVersionBox.getItems().clear();
            modVersionBox.getSelectionModel().clearSelection();
            modVersionBox.setDisable(true);
            installVersionStatusLabel.setText("Loading compatible versions...");
            resultsStatusLabel.setText(query.isBlank()
                    ? "Loading compatible mods..."
                    : "Searching compatible mods for \"" + query + "\"...");
            previewStatusLabel.setText("Searching compatible mods...");
            preview.getEngine().loadContent(buildModProjectPreviewHtml(
                    context,
                    null,
                    null,
                    query.isBlank()
                            ? "Loading compatible mods for this profile..."
                            : "Searching compatible mods for \"" + query + "\"..."
            ));
            io.submit(() -> {
                try {
                    List<ModrinthService.Project> projects = modrinthService.searchProjects(
                            query,
                            context.loader(),
                            context.gameVersion(),
                            context.side()
                    );
                    Platform.runLater(() -> {
                        if (searchRequestId[0] != requestId) {
                            return;
                        }
                        results.setDisable(false);
                        results.setItems(FXCollections.observableArrayList(projects));
                        if (!projects.isEmpty()) {
                            resultsStatusLabel.setText("Showing " + projects.size() + " compatible mods for " + context.gameVersion()
                                    + " " + LoaderFamily.fromId(context.loader()).displayName() + ".");
                            results.getSelectionModel().selectFirst();
                        } else {
                            resultsStatusLabel.setText("No compatible mods found for this profile.");
                            previewStatusLabel.setText("No compatible mods found");
                            installVersionStatusLabel.setText("No compatible mods found for this profile.");
                            preview.getEngine().loadContent(buildModProjectPreviewHtml(
                                    context,
                                    null,
                                    null,
                                    "No compatible Modrinth results were found for this profile."
                            ));
                        }
                    });
                } catch (Exception ex) {
                    appendLog("[Limecraft] Mod browser search failed: " + ex.getMessage());
                    Platform.runLater(() -> {
                        if (searchRequestId[0] != requestId) {
                            return;
                        }
                        results.setDisable(false);
                        results.setItems(FXCollections.emptyObservableList());
                        resultsStatusLabel.setText("Failed to load compatible mods.");
                        previewStatusLabel.setText("Search failed");
                        installVersionStatusLabel.setText("Failed to load compatible mods.");
                        preview.getEngine().loadContent(buildModProjectPreviewHtml(
                                context,
                                null,
                                null,
                                "Modrinth search failed: " + ex.getMessage()
                        ));
                    });
                }
            });
        };

        Button searchButton = new Button("Search");
        searchButton.setOnAction(e -> performSearch.run());
        Button clearButton = new Button("Clear");
        clearButton.setDisable(true);
        clearButton.setOnAction(e -> {
            queryField.clear();
            performSearch.run();
        });
        queryField.textProperty().addListener((obs, oldVal, newVal) ->
                clearButton.setDisable(newVal == null || newVal.trim().isBlank()));
        queryField.setOnAction(e -> performSearch.run());
        showAllVersionsToggle.selectedProperty().addListener((obs, oldVal, newVal) -> refreshSupportedMinecraftVersions[0].run());
        supportedMinecraftBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshInstallSelection[0].run());
        modVersionBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshInstallSelection[0].run());

        results.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, project) -> {
            if (project == null) {
                projectVersionsRef[0] = List.of();
                installButton.setDisable(true);
                openPageButton.setDisable(true);
                previewStatusLabel.setText("No mod selected");
                installVersionStatusLabel.setText("Select a mod to load supported Minecraft and mod versions.");
                supportedMinecraftBox.getItems().clear();
                supportedMinecraftBox.getSelectionModel().clearSelection();
                supportedMinecraftBox.setDisable(true);
                modVersionBox.getItems().clear();
                modVersionBox.getSelectionModel().clearSelection();
                modVersionBox.setDisable(true);
                preview.getEngine().loadContent(buildModProjectPreviewHtml(
                        context,
                        null,
                        null,
                        "Search Modrinth and select a compatible mod to preview it here."
                ));
                return;
            }

            openPageButton.setDisable(false);
            previewStatusLabel.setText(formatModProjectSelectionMeta(project));
            installVersionStatusLabel.setText("Loading supported Minecraft and mod versions...");
            supportedMinecraftBox.getItems().clear();
            supportedMinecraftBox.getSelectionModel().clearSelection();
            supportedMinecraftBox.setDisable(true);
            modVersionBox.getItems().clear();
            modVersionBox.getSelectionModel().clearSelection();
            modVersionBox.setDisable(true);
            preview.getEngine().loadContent(buildModProjectPreviewHtml(
                    context,
                    project,
                    null,
                    "Loading project details..."
            ));
            long requestId = ++detailRequestId[0];
            io.submit(() -> {
                try {
                    ModrinthService.ProjectDetails details = modrinthService.getProjectDetails(project.id());
                    List<ModrinthService.Version> versions = modrinthService.listVersions(project.id(), context.loader());
                    Platform.runLater(() -> {
                        ModrinthService.Project selectedProject = results.getSelectionModel().getSelectedItem();
                        if (detailRequestId[0] != requestId
                                || selectedProject == null
                                || !selectedProject.id().equalsIgnoreCase(project.id())) {
                            return;
                        }
                        projectVersionsRef[0] = versions;
                        previewStatusLabel.setText(formatModProjectSelectionMeta(project));
                        preview.getEngine().loadContent(buildModProjectPreviewHtml(context, project, details, ""));
                        refreshSupportedMinecraftVersions[0].run();
                    });
                } catch (Exception ex) {
                    appendLog("[Limecraft] Failed to load Modrinth project details: " + ex.getMessage());
                    Platform.runLater(() -> {
                        ModrinthService.Project selectedProject = results.getSelectionModel().getSelectedItem();
                        if (detailRequestId[0] != requestId
                                || selectedProject == null
                                || !selectedProject.id().equalsIgnoreCase(project.id())) {
                            return;
                        }
                        projectVersionsRef[0] = List.of();
                        previewStatusLabel.setText(formatModProjectSelectionMeta(project));
                        refreshSupportedMinecraftVersions[0].run();
                        preview.getEngine().loadContent(buildModProjectPreviewHtml(
                                context,
                                project,
                                null,
                                "Extended details could not be loaded. Showing search result summary."
                        ));
                    });
                }
            });
        });

        installButton.setOnAction(e -> {
            ModrinthService.Project project = results.getSelectionModel().getSelectedItem();
            ModrinthService.Version selectedVersion = modVersionBox.getValue();
            if (project == null) {
                setStatus("Select a project first.", 0);
                return;
            }
            if (selectedVersion == null) {
                setStatus("Select a compatible mod version first.", 0);
                return;
            }
            io.submit(() -> {
                try {
                    setStatus("Installing " + formatModVersionShortLabel(selectedVersion) + " for " + project.title() + "...", 0.2);
                    Path modsDir = modsDirFor(context.target(), context.side());
                    Path file = modrinthService.downloadPrimaryFile(selectedVersion, modsDir);
                    String dependencyNote = selectedVersion.dependencyCount() > 0
                            ? " (" + selectedVersion.dependencyCount() + " dependencies not auto-installed)"
                            : "";
                    setStatus("Installed " + file.getFileName() + " to " + context.side() + " mods" + dependencyNote, 0);
                } catch (Exception ex) {
                    fail(ex);
                }
            });
        });

        openPageButton.setOnAction(e -> {
            ModrinthService.Project project = results.getSelectionModel().getSelectedItem();
            if (project == null) {
                setStatus("Select a project first.", 0);
                return;
            }
            openExternalUrl("https://modrinth.com/mod/" + project.slug(), "Opened " + project.title() + " on Modrinth.");
        });

        HBox filters = new HBox(8,
                labeledNode("Search", queryField),
                searchButton,
                clearButton
        );
        HBox.setHgrow(queryField, Priority.ALWAYS);

        HBox actionRow = new HBox(8, installButton, openFolderButton, openPageButton);
        VBox left = new VBox(8, scopeLabel, resultsStatusLabel, results, actionRow);
        VBox.setVgrow(results, Priority.ALWAYS);

        VBox compatibilityBox = new VBox(
                8,
                showAllVersionsToggle,
                labeledNode("Supported Minecraft", supportedMinecraftBox),
                labeledNode("Mod Version", modVersionBox),
                installVersionStatusLabel
        );
        compatibilityBox.getStyleClass().add("settings-card");

        VBox right = new VBox(8, previewStatusLabel, compatibilityBox, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox root = new VBox(12, filters, split);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 1280, 780);
        scene.getStylesheets().add(getClass().getResource("/limecraft.css").toExternalForm());
        dialog.setScene(scene);
        dialog.show();
        performSearch.run();
    }

    private String formatModProjectListMeta(ModrinthService.Project project) {
        List<String> segments = new ArrayList<>();
        if (project.author() != null && !project.author().isBlank()) {
            segments.add("By " + project.author().trim());
        }
        segments.add("Client " + formatSideSupport(project.clientSide()));
        segments.add("Server " + formatSideSupport(project.serverSide()));
        if (project.downloads() > 0) {
            segments.add(formatCompactCount(project.downloads()) + " downloads");
        }
        return String.join(" | ", segments);
    }

    private String formatModProjectSelectionMeta(ModrinthService.Project project) {
        return project.title()
                + " | Client " + formatSideSupport(project.clientSide())
                + " | Server " + formatSideSupport(project.serverSide());
    }

    private List<String> supportedMinecraftVersionsForProject(List<ModrinthService.Version> versions, boolean showAllVersions) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (ModrinthService.Version version : versions) {
            if (version == null) {
                continue;
            }
            for (String gameVersion : version.gameVersions()) {
                if (gameVersion == null || gameVersion.isBlank()) {
                    continue;
                }
                if (showAllVersions || isReleaseMinecraftVersion(gameVersion)) {
                    ordered.add(gameVersion.trim());
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private List<ModrinthService.Version> compatibleModVersions(
            List<ModrinthService.Version> versions,
            String minecraftVersion,
            boolean showAllVersions
    ) {
        List<ModrinthService.Version> compatible = new ArrayList<>();
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return compatible;
        }
        for (ModrinthService.Version version : versions) {
            if (version == null || version.files().isEmpty()) {
                continue;
            }
            if (!version.gameVersions().contains(minecraftVersion)) {
                continue;
            }
            if (!showAllVersions && !isReleaseModVersion(version)) {
                continue;
            }
            compatible.add(version);
        }
        return compatible;
    }

    private boolean isReleaseMinecraftVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        VersionEntry known = findVersionById(versionId.trim());
        if (known != null && known.url() != null && !known.url().isBlank()) {
            return "release".equalsIgnoreCase(known.type());
        }
        return versionId.trim().matches("\\d+(?:\\.\\d+)+");
    }

    private boolean isReleaseModVersion(ModrinthService.Version version) {
        if (version == null || version.versionType() == null || version.versionType().isBlank()) {
            return true;
        }
        return "release".equalsIgnoreCase(version.versionType().trim());
    }

    private String formatModVersionLabel(ModrinthService.Version version) {
        if (version == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(formatModVersionShortLabel(version));
        if (version.publishedAt() != null && !version.publishedAt().isBlank()) {
            parts.add(formatIsoDate(version.publishedAt()));
        }
        if (version.gameVersions() != null && !version.gameVersions().isEmpty()) {
            parts.add("MC " + summarizeGameVersions(version.gameVersions(), 3));
        }
        return String.join(" | ", parts);
    }

    private String formatModVersionShortLabel(ModrinthService.Version version) {
        if (version == null) {
            return "";
        }
        String number = preferredText(version.versionNumber(), version.name(), "unknown");
        String type = version.versionType() == null || version.versionType().isBlank()
                ? ""
                : " [" + version.versionType().trim().toLowerCase(Locale.ROOT) + "]";
        return number + type;
    }

    private String summarizeGameVersions(List<String> gameVersions, int limit) {
        if (gameVersions == null || gameVersions.isEmpty()) {
            return "";
        }
        List<String> trimmed = new ArrayList<>();
        for (String gameVersion : gameVersions) {
            if (gameVersion == null || gameVersion.isBlank()) {
                continue;
            }
            trimmed.add(gameVersion.trim());
            if (trimmed.size() >= limit) {
                break;
            }
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", trimmed);
        if (gameVersions.size() > trimmed.size()) {
            joined += ", +" + (gameVersions.size() - trimmed.size());
        }
        return joined;
    }

    private String formatSideSupport(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "required" -> "required";
            case "optional" -> "optional";
            case "unsupported" -> "unsupported";
            default -> normalized;
        };
    }

    private String formatModProjectCategoryLine(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return "No categories listed";
        }
        List<String> trimmed = new ArrayList<>();
        for (String category : categories) {
            if (category == null || category.isBlank()) {
                continue;
            }
            trimmed.add(category.trim());
            if (trimmed.size() >= 5) {
                break;
            }
        }
        return trimmed.isEmpty() ? "No categories listed" : String.join(" | ", trimmed);
    }

    private String trimPreviewText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private String buildModProjectPreviewHtml(
            ModBrowserContext context,
            ModrinthService.Project project,
            ModrinthService.ProjectDetails details,
            String notice
    ) {
        String targetLabel = context.target().id() + " | Minecraft " + context.gameVersion()
                + " | " + LoaderFamily.fromId(context.loader()).displayName()
                + " | " + context.side() + " mods";
        if (project == null && details == null) {
            return """
                    <html>
                    <head>
                    <style>
                    body { background:#101318; color:#d5dbe4; font-family:'Segoe UI',sans-serif; padding:24px; }
                    .notice { color:#a8ff83; font-size:16px; margin:0 0 14px 0; }
                    .target { color:#8f9bac; font-size:13px; }
                    </style>
                    </head>
                    <body>
                    <div class='notice'>%s</div>
                    <div class='target'>%s</div>
                    </body>
                    </html>
                    """.formatted(escapeHtml(notice), escapeHtml(targetLabel));
        }

        String title = preferredText(details == null ? "" : details.title(), project == null ? "" : project.title(), "Unknown project");
        String author = preferredText(details == null ? "" : details.author(), project == null ? "" : project.author(), "Unknown author");
        String description = preferredText(details == null ? "" : details.description(), project == null ? "" : project.description(), "No description provided.");
        String body = preferredText(details == null ? "" : details.body(), description, "No summary provided.");
        List<String> categories = details != null && details.categories() != null && !details.categories().isEmpty()
                ? details.categories()
                : (project == null ? List.of() : project.categories());
        long downloads = details != null && details.downloads() > 0 ? details.downloads() : (project == null ? 0 : project.downloads());
        long follows = details != null && details.follows() > 0 ? details.follows() : (project == null ? 0 : project.follows());
        String clientSide = preferredText(details == null ? "" : details.clientSide(), project == null ? "" : project.clientSide(), "unknown");
        String serverSide = preferredText(details == null ? "" : details.serverSide(), project == null ? "" : project.serverSide(), "unknown");
        String iconUrl = preferredText(details == null ? "" : details.iconUrl(), project == null ? "" : project.iconUrl(), "");

        String noticeHtml = notice == null || notice.isBlank()
                ? ""
                : "<div class='notice'>" + escapeHtml(notice) + "</div>";
        String iconHtml = iconUrl.isBlank()
                ? ""
                : "<img class='icon' src=\"" + escapeHtml(iconUrl) + "\" alt=\"Project icon\" />";
        String categoryHtml = renderCategoryBadges(categories);
        String bodyHtml = escapeHtml(body)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br>");

        return """
                <html>
                <head>
                <style>
                body { background:#101318; color:#d5dbe4; font-family:'Segoe UI',sans-serif; margin:0; padding:22px; }
                .notice { background:#192228; color:#a8ff83; border:1px solid #2b3442; border-radius:10px; padding:10px 12px; margin-bottom:16px; }
                .hero { display:flex; gap:18px; align-items:flex-start; }
                .hero-main { flex:1; }
                .eyebrow { color:#8f9bac; font-size:12px; margin-bottom:8px; letter-spacing:0.2px; }
                h1 { margin:0 0 8px 0; color:#ecf1f7; font-size:28px; }
                .byline { color:#a8ff83; font-size:14px; margin-bottom:14px; }
                .icon { width:72px; height:72px; border-radius:16px; border:1px solid #2b3442; background:#171c23; }
                .chips { margin:0 0 12px 0; }
                .chip { display:inline-block; margin:0 8px 8px 0; padding:5px 10px; border-radius:999px; background:#1a2029; border:1px solid #2b3442; color:#d5dbe4; font-size:12px; }
                .chip.good { background:#20422a; color:#d8ffd2; border-color:#366844; }
                .chip.warn { background:#4d3d14; color:#ffe6a0; border-color:#7d6320; }
                .chip.bad { background:#472323; color:#ffd3d3; border-color:#744040; }
                .chip.muted { color:#8f9bac; }
                .stats { color:#b4becc; font-size:13px; margin-bottom:16px; }
                .section-title { color:#a8ff83; font-size:13px; margin:18px 0 8px 0; text-transform:uppercase; letter-spacing:0.5px; }
                .summary { background:#151b22; border:1px solid #2b3442; border-radius:12px; padding:14px 16px; line-height:1.5; white-space:normal; }
                .footer { color:#8f9bac; font-size:12px; margin-top:16px; }
                a { color:#a8ff83; }
                </style>
                </head>
                <body>
                %s
                <div class='hero'>
                  <div class='hero-main'>
                    <div class='eyebrow'>Compatible with %s</div>
                    <h1>%s</h1>
                    <div class='byline'>By %s</div>
                    <div class='chips'>%s %s %s</div>
                    <div class='stats'>%s</div>
                  </div>
                  %s
                </div>
                <div class='section-title'>Summary</div>
                <div class='summary'>%s</div>
                <div class='footer'>Install target: %s</div>
                </body>
                </html>
                """.formatted(
                noticeHtml,
                escapeHtml(context.gameVersion() + " | " + LoaderFamily.fromId(context.loader()).displayName() + " | " + context.side()),
                escapeHtml(title),
                escapeHtml(author),
                renderSupportBadge("Client", clientSide),
                renderSupportBadge("Server", serverSide),
                categoryHtml,
                escapeHtml(buildModProjectStats(downloads, follows, description)),
                iconHtml,
                bodyHtml,
                escapeHtml(targetLabel)
        );
    }

    private String buildModProjectStats(long downloads, long follows, String description) {
        List<String> segments = new ArrayList<>();
        if (downloads > 0) {
            segments.add(formatCompactCount(downloads) + " downloads");
        }
        if (follows > 0) {
            segments.add(formatCompactCount(follows) + " followers");
        }
        if (description != null && !description.isBlank()) {
            segments.add(trimPreviewText(description, 140));
        }
        return segments.isEmpty() ? "Compatible project" : String.join(" | ", segments);
    }

    private String renderSupportBadge(String label, String value) {
        String normalized = formatSideSupport(value);
        String styleClass = switch (normalized) {
            case "required" -> "good";
            case "optional" -> "warn";
            case "unsupported" -> "bad";
            default -> "muted";
        };
        return "<span class='chip " + styleClass + "'>" + escapeHtml(label + ": " + normalized) + "</span>";
    }

    private String renderCategoryBadges(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return "<span class='chip muted'>No categories listed</span>";
        }
        StringBuilder html = new StringBuilder();
        int count = 0;
        for (String category : categories) {
            if (category == null || category.isBlank()) {
                continue;
            }
            html.append("<span class='chip'>").append(escapeHtml(category.trim())).append("</span>");
            count++;
            if (count >= 6) {
                break;
            }
        }
        return count == 0 ? "<span class='chip muted'>No categories listed</span>" : html.toString();
    }

    private String preferredText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String escapeHtml(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String formatCompactCount(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fb", value / 1_000_000_000.0).replace(".0", "");
        }
        if (value >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fm", value / 1_000_000.0).replace(".0", "");
        }
        if (value >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0).replace(".0", "");
        }
        return Long.toString(value);
    }

    private void openExternalUrl(String url, String successStatus) {
        if (url == null || url.isBlank()) {
            setStatus("Nothing to open.", 0);
            return;
        }
        io.submit(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("Desktop integration is not supported on this system.");
                }
                Desktop.getDesktop().browse(URI.create(url));
                setStatus(successStatus, 0);
            } catch (Exception ex) {
                fail(ex);
            }
        });
    }

    private void checkForLauncherUpdates() {
        io.submit(() -> {
            try {
                LauncherUpdateService.ReleaseInfo latestRelease = updateService.fetchLatestRelease();
                if (updateService.isNewerThanCurrent(latestRelease)) {
                    availableUpdate = latestRelease;
                    Platform.runLater(() -> {
                        updateUpdateIndicator();
                        appendLog("[Limecraft] Update " + latestRelease.version() + " is available.");
                    });
                } else {
                    availableUpdate = null;
                    Platform.runLater(this::updateUpdateIndicator);
                }
            } catch (Exception ex) {
                appendLog("[Limecraft] Update check failed: " + ex.getMessage());
            }
        });
    }

    private void triggerAvailableUpdate() {
        LauncherUpdateService.ReleaseInfo release = availableUpdate;
        if (release == null) {
            setStatus("No launcher update is ready.", 0);
            return;
        }

        String releaseUrl = release.htmlUrl().isBlank() ? AppVersion.RELEASES_URL : release.htmlUrl();
        if (!appPaths.canSelfUpdate() || release.asset() == null) {
            openExternalUrl(releaseUrl, "Opened launcher release page");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setHeaderText("Install launcher update " + release.version() + "?");
        confirm.setContentText("Limecraft will close, download the new packaged zip, replace the current app folder, and reopen.");
        ButtonType installButton = new ButtonType("Install Update", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(installButton, ButtonType.CANCEL);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != installButton) {
            return;
        }

        try {
            Path updaterScript = updateService.writeWindowsUpdaterScript(release, appPaths, ProcessHandle.current().pid());
            appendLog("[Limecraft] Starting updater for " + release.version() + " using " + updaterScript);
            new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    updaterScript.toString()
            ).start();
            Platform.exit();
        } catch (Exception ex) {
            fail(ex);
        }
    }

    private void updateUpdateIndicator() {
        if (launcherShell == null) {
            return;
        }
        LauncherUpdateService.ReleaseInfo release = availableUpdate;
        boolean available = release != null;
        String text = available ? "Update " + release.version() : "Update";
        String tooltip = available
                ? "New release " + release.version() + " is ready to install."
                : "";
        launcherShell.setUpdateStatus(text, tooltip, available);
    }

    private void updateJobIndicator(int jobCount) {
        Platform.runLater(() -> {
            if (launcherShell == null) {
                return;
            }
            boolean active = jobCount > 0;
            launcherShell.setJobStatus(
                    active ? "Jobs " + jobCount : "Idle",
                    active ? jobCount + " launcher jobs are running or queued." : "No launcher jobs are running.",
                    active
            );
        });
    }

    private void updateAccountIndicator() {
        Platform.runLater(() -> {
            if (launcherShell == null) {
                return;
            }
            String text;
            String tooltip;
            boolean activeSession;
            if (MODE_OFFLINE.equals(savedAccountMode)) {
                activeSession = true;
                text = "Offline " + savedOfflineUsername;
                tooltip = "Offline mode using username " + savedOfflineUsername + ".";
            } else if (signedInAccount != null) {
                activeSession = true;
                text = "Account " + signedInAccount.username();
                tooltip = "Signed in with Microsoft as " + signedInAccount.username() + ".";
            } else if (microsoftSignInInProgress) {
                activeSession = false;
                text = "Account Signing in";
                tooltip = "Microsoft device sign-in is in progress.";
            } else {
                SavedMicrosoftAccount saved = selectedSavedAccount();
                activeSession = false;
                text = saved == null ? "Account Not signed in" : "Account " + saved.username();
                tooltip = saved == null
                        ? "Microsoft mode without an active session."
                        : "Saved Microsoft account selected: " + saved.username() + ".";
            }
            launcherShell.setAccountStatus(text, tooltip, activeSession);
        });
    }

    private void updateErrorIndicator() {
        if (launcherShell == null) {
            return;
        }
        boolean visible = lastLauncherErrorSummary != null && !lastLauncherErrorSummary.isBlank();
        String tooltip = visible ? lastLauncherErrorSummary : "";
        launcherShell.setErrorStatus("Report Error", tooltip, visible);
    }

    private void reportLauncherError() {
        if (lastLauncherErrorSummary == null || lastLauncherErrorSummary.isBlank()) {
            setStatus("No launcher error is ready to report.", 0);
            return;
        }
        copyToClipboard(lastLauncherErrorSummary);
        openExternalUrl(AppVersion.ISSUES_URL, "Opened launcher issue page and copied error details");
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private Path modsDirFor(VersionEntry version, String side) {
        if ("server".equalsIgnoreCase(side)) {
            return serverDirFor(version).resolve("mods");
        }
        return instanceDirFor(version).resolve("mods");
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
            String message = friendlyErrorMessage(ex);
            appendExceptionDetails(ex);

            CrashReportAnalyzer.DiagnosisReport diagnosis = resolveSelectedClientDiagnosis();
            String likelyCause = resolveLikelyCause(message, diagnosis);
            String suggestedFix = resolveSuggestedFix(message, diagnosis);
            lastLauncherErrorSummary = buildLauncherErrorSummary(message, likelyCause, suggestedFix, diagnosis);
            updateErrorIndicator();

            status.setText("Error: " + message);
            appendLog("[Limecraft] Error: " + message);
            if (!likelyCause.isBlank()) {
                appendLog("[Limecraft] Likely cause: " + likelyCause);
            }
            if (!suggestedFix.isBlank()) {
                appendLog("[Limecraft] Suggested fix: " + suggestedFix);
            }
            progress.setProgress(0);
            ButtonType copyDetailsButton = new ButtonType("Copy Details", ButtonBar.ButtonData.OTHER);
            ButtonType reportIssueButton = new ButtonType("Report Issue", ButtonBar.ButtonData.LEFT);
            ButtonType openLogButton = diagnosis != null && diagnosis.latestLog() != null
                    ? new ButtonType("Open Latest Log", ButtonBar.ButtonData.RIGHT)
                    : null;
            ButtonType openCrashButton = diagnosis != null && diagnosis.latestCrashReport() != null
                    ? new ButtonType("Open Crash Report", ButtonBar.ButtonData.RIGHT)
                    : null;

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Limecraft Error");
            dialog.setHeaderText("Limecraft error");
            List<ButtonType> buttonTypes = new ArrayList<>();
            buttonTypes.add(ButtonType.OK);
            buttonTypes.add(copyDetailsButton);
            buttonTypes.add(reportIssueButton);
            if (openLogButton != null) {
                buttonTypes.add(openLogButton);
            }
            if (openCrashButton != null) {
                buttonTypes.add(openCrashButton);
            }
            dialog.getDialogPane().getButtonTypes().setAll(buttonTypes);
            dialog.getDialogPane().setContent(buildFailureDialogContent(message, likelyCause, suggestedFix, diagnosis));
            ButtonType result = dialog.showAndWait().orElse(ButtonType.OK);
            if (result == copyDetailsButton) {
                copyToClipboard(lastLauncherErrorSummary);
                setStatus("Copied launcher error details to clipboard.", 0);
            } else if (result == reportIssueButton) {
                reportLauncherError();
            } else if (openLogButton != null && result == openLogButton) {
                openDesktopPath(diagnosis.latestLog(), "Opened latest log");
            } else if (openCrashButton != null && result == openCrashButton) {
                openDesktopPath(diagnosis.latestCrashReport(), "Opened latest crash report");
            }
        });
    }

    private String friendlyErrorMessage(Exception ex) {
        if (ex == null) {
            return "Unknown launcher error.";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.trim();
    }

    private String resolveLikelyCause(String message, CrashReportAnalyzer.DiagnosisReport diagnosis) {
        if (diagnosis != null && !diagnosis.primaryCause().isBlank()) {
            return diagnosis.primaryCause();
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("access is denied") || normalized.contains("permission denied")) {
            return "The launcher could not read or write one of its files.";
        }
        if (normalized.contains("desktop integration")) {
            return "This machine is blocking the launcher from opening external files or URLs.";
        }
        if (normalized.contains("no refresh token")) {
            return "The saved Microsoft session no longer has a usable refresh token.";
        }
        return "The launcher hit an internal error before it could complete the action.";
    }

    private String resolveSuggestedFix(String message, CrashReportAnalyzer.DiagnosisReport diagnosis) {
        if (diagnosis != null && !diagnosis.primaryFix().isBlank()) {
            return diagnosis.primaryFix();
        }
        return suggestLauncherFix(message);
    }

    private String suggestLauncherFix(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("access is denied") || normalized.contains("permission denied")) {
            return "Close any running Limecraft build, then retry from a writable folder.";
        }
        if (normalized.contains("java 8")) {
            return "Set the Java Path to a Java 8 runtime for this older profile.";
        }
        if (normalized.contains("desktop integration")) {
            return "Open the link manually in your browser if this machine blocks desktop integration.";
        }
        if (normalized.contains("self-update is only available")) {
            return "Run the packaged app image from the release zip, or update manually from GitHub Releases.";
        }
        if (normalized.contains("no refresh token")) {
            return "Sign in again with Microsoft so Limecraft can refresh the session.";
        }
        return "Check the launcher log output, then use Report Issue if the error keeps happening.";
    }

    private String buildLauncherErrorSummary(String message, String likelyCause, String suggestedFix, CrashReportAnalyzer.DiagnosisReport diagnosis) {
        VersionEntry selectedClient = versionsList == null ? null : versionsList.getSelectionModel().getSelectedItem();
        VersionEntry selectedServer = serverVersionsList == null ? null : serverVersionsList.getSelectionModel().getSelectedItem();
        return String.join(System.lineSeparator(),
                "Launcher version: " + AppVersion.CURRENT,
                "Storage mode: " + appPaths.storageModeLabel(),
                "Data directory: " + gameDir.toAbsolutePath(),
                "OS: " + System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""),
                "Client profile: " + (selectedClient == null ? "none" : selectedClient.id()),
                "Server profile: " + (selectedServer == null ? "none" : selectedServer.id()),
                "Error: " + message,
                "Likely cause: " + likelyCause,
                "Latest log: " + (diagnosis == null || diagnosis.latestLog() == null ? "none" : diagnosis.latestLog()),
                "Latest crash report: " + (diagnosis == null || diagnosis.latestCrashReport() == null ? "none" : diagnosis.latestCrashReport()),
                "Suggested fix: " + suggestedFix
        );
    }

    private void appendExceptionDetails(Exception ex) {
        if (ex == null) {
            return;
        }
        StringWriter buffer = new StringWriter();
        ex.printStackTrace(new PrintWriter(buffer));
        appendLog(buffer.toString().trim());
    }

    private CrashReportAnalyzer.DiagnosisReport resolveSelectedClientDiagnosis() {
        try {
            VersionEntry selected = versionsList == null ? null : versionsList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return null;
            }
            return crashReportAnalyzer.analyze(instanceDirFor(selected));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Node buildFailureDialogContent(String summary, String likelyCause, String suggestedFix, CrashReportAnalyzer.DiagnosisReport diagnosis) {
        VBox root = new VBox(10,
                detailSection("Summary", summary),
                detailSection("Likely Cause", likelyCause),
                detailSection("Suggested Fix", suggestedFix)
        );
        if (diagnosis != null) {
            root.getChildren().add(detailSection("Diagnosis", diagnosis.formatForClipboard()));
        }
        root.setPadding(new Insets(4, 0, 0, 0));
        root.setPrefWidth(620);
        return root;
    }

    private VBox detailSection(String title, String body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("selected-version");
        TextArea content = new TextArea(body == null || body.isBlank() ? "None" : body.trim());
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefRowCount(Math.max(2, Math.min(8, content.getText().split("\\R").length + 1)));
        VBox box = new VBox(4, heading, content);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private void showDiagnosisDialog(String title, CrashReportAnalyzer.DiagnosisReport diagnosis) {
        ButtonType openLogButton = diagnosis.latestLog() == null
                ? null
                : new ButtonType("Open Latest Log", ButtonBar.ButtonData.RIGHT);
        ButtonType openCrashButton = diagnosis.latestCrashReport() == null
                ? null
                : new ButtonType("Open Crash Report", ButtonBar.ButtonData.RIGHT);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        List<ButtonType> buttons = new ArrayList<>();
        buttons.add(ButtonType.CLOSE);
        if (openLogButton != null) {
            buttons.add(openLogButton);
        }
        if (openCrashButton != null) {
            buttons.add(openCrashButton);
        }
        dialog.getDialogPane().getButtonTypes().setAll(buttons);
        dialog.getDialogPane().setContent(buildFailureDialogContent(
                diagnosis.summary(),
                formatList(diagnosis.likelyCauses()),
                formatList(diagnosis.suggestedFixes()),
                diagnosis
        ));
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CLOSE);
        if (openLogButton != null && result == openLogButton) {
            openDesktopPath(diagnosis.latestLog(), "Opened latest log");
        } else if (openCrashButton != null && result == openCrashButton) {
            openDesktopPath(diagnosis.latestCrashReport(), "Opened latest crash report");
        }
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> "- " + value.trim())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private void openDesktopPath(Path path, String successStatus) {
        if (path == null || !Files.exists(path)) {
            setStatus("Nothing to open.", 0);
            return;
        }
        io.submit(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("Desktop integration is not supported on this system.");
                }
                Desktop.getDesktop().open(path.toFile());
                setStatus(successStatus, 0);
            } catch (Exception ex) {
                fail(ex);
            }
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
        recordPlaySessionForCurrentLaunch();
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

        ensureVersionAvailableLocally(parentId);
        Path parentJson = findVersionJson(parentId);
        if (!Files.exists(parentJson)) {
            VersionEntry parent = findVersionById(parentId);
            if (parent == null || parent.url() == null || parent.url().isBlank()) {
                throw new IllegalStateException("Missing inherited version metadata for " + parentId);
            }
            setStatus("Installing inherited base " + parentId + "...", 0.4);
            installService.installVersion(parent, this::setStatus);
        } else if (!versionJarExists(parentId)) {
            VersionEntry parent = findVersionById(parentId);
            if (parent != null && parent.url() != null && !parent.url().isBlank()) {
                setStatus("Installing missing inherited jar/assets for " + parentId + "...", 0.45);
                installService.installVersion(parent, this::setStatus);
            }
        }

        JsonObject parentMeta = JsonParser.parseString(Files.readString(parentJson)).getAsJsonObject();
        installService.installMetadataDependenciesIfNeeded(parentMeta, this::setStatus);
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
        if (!running) {
            updateClientModBrowserButtonState();
        }
    }

    private boolean isVersionInstalled(VersionEntry version) {
        if (version == null) {
            return false;
        }
        Boolean cached = installedClientVersionCache.get(version.id());
        if (cached != null) {
            return cached;
        }
        boolean installed = isVersionInstalledOnDisk(version);
        installedClientVersionCache.put(version.id(), installed);
        return installed;
    }

    private boolean isVersionInstalledOnDisk(VersionEntry version) {
        Path json = findVersionJson(version.id());
        if (!Files.exists(json)) {
            return false;
        }
        if (versionJarExists(version.id())) {
            return true;
        }
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (!meta.has("inheritsFrom")) {
                return false;
            }
            String parent = meta.get("inheritsFrom").getAsString();
            return versionJarExists(parent);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean versionJarExists(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        Path localJar = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        if (Files.exists(localJar)) {
            return true;
        }
        Path legacyJar = appPaths.legacyDataDir().resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        return Files.exists(legacyJar);
    }
    private void applyVersionFilter() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean includeExperimental = experimentToggle.isSelected();
        VersionEntry currentSelection = versionsList.getSelectionModel().getSelectedItem();
        String preferredId = currentSelection != null ? currentSelection.id() : lastSelectedVersionId;

        List<VersionEntry> filtered = new ArrayList<>();
        for (VersionEntry v : allVersions) {
            if (!v.supportsClient()) {
                continue;
            }
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
        updateClientModBrowserButtonState();
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
            String refreshToken = props.getProperty(KEY_MICROSOFT_REFRESH_TOKEN);
            if (refreshToken != null && !refreshToken.isBlank()) {
                savedMicrosoftRefreshToken = refreshToken.trim();
            }
            String savedAccountId = props.getProperty(KEY_SELECTED_ACCOUNT_ID);
            if (savedAccountId != null && !savedAccountId.isBlank()) {
                selectedAccountId = savedAccountId.trim();
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
            if (accountModeBox != null && Platform.isFxApplicationThread() && accountModeBox.getValue() != null) {
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
            if (selectedAccountId != null && !selectedAccountId.isBlank()) {
                props.setProperty(KEY_SELECTED_ACCOUNT_ID, selectedAccountId);
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
        persistSelectedServerSettings();
        recordPlaySessionForCurrentLaunch();
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





















































































