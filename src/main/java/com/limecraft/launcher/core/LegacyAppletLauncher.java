package com.limecraft.launcher.core;

import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.applet.AudioClip;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public final class LegacyAppletLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Applet class name is required.");
        }
        String appletClass = args[0];
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(arg.substring(0, eq), arg.substring(eq + 1));
        }

        int width = parseInt(params.getOrDefault("width", "854"), 854);
        int height = parseInt(params.getOrDefault("height", "480"), 480);

        Applet applet = (Applet) Class.forName(appletClass).getDeclaredConstructor().newInstance();
        Frame frame = new Frame("Minecraft");
        frame.setLayout(new BorderLayout());
        frame.add(applet, BorderLayout.CENTER);
        frame.setSize(width, height);
        frame.setResizable(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    applet.stop();
                    applet.destroy();
                } catch (Exception ignored) {
                }
                frame.dispose();
                System.exit(0);
            }
        });

        LegacyAppletStub stub = new LegacyAppletStub(params, frame);
        applet.setStub(stub);
        applet.setSize(width, height);
        frame.setVisible(true);
        applet.init();
        applet.start();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class LegacyAppletStub implements AppletStub, AppletContext {
        private final Map<String, String> params;
        private final Frame frame;
        private final URL baseUrl;

        private LegacyAppletStub(Map<String, String> params, Frame frame) throws Exception {
            this.params = params == null ? Map.of() : params;
            this.frame = frame;
            String gameDir = this.params.getOrDefault("gameDir", ".");
            this.baseUrl = new File(gameDir).toURI().toURL();
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public URL getDocumentBase() {
            return baseUrl;
        }

        @Override
        public URL getCodeBase() {
            return baseUrl;
        }

        @Override
        public String getParameter(String name) {
            return params.get(name);
        }

        @Override
        public AppletContext getAppletContext() {
            return this;
        }

        @Override
        public void appletResize(int width, int height) {
            frame.setSize(width, height);
        }

        @Override
        public AudioClip getAudioClip(URL url) {
            return null;
        }

        @Override
        public Image getImage(URL url) {
            return Toolkit.getDefaultToolkit().getImage(url);
        }

        @Override
        public Applet getApplet(String name) {
            return null;
        }

        @Override
        public Enumeration<Applet> getApplets() {
            return Collections.emptyEnumeration();
        }

        @Override
        public void showDocument(URL url) {
        }

        @Override
        public void showDocument(URL url, String target) {
        }

        @Override
        public void showStatus(String status) {
        }

        @Override
        public void setStream(String key, InputStream stream) {
        }

        @Override
        public InputStream getStream(String key) {
            return null;
        }

        @Override
        public java.util.Iterator<String> getStreamKeys() {
            return Collections.emptyIterator();
        }
    }
}
