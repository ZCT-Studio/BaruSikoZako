// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import io.github.zct_studio.barusikozako.backend.ConfigUnits;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/**
 * 使用 Java 原始 Swing UI 的独立工作站界面。
 *
 * <p>覆盖游戏内全部离线能力：备份（各模式 + 备注）、列出、详情、校验、回滚、删除、
 * 状态与配置查看。所有操作在后台线程执行，进度与消息写入日志区。
 */
public final class StandaloneGui {
    private static final String[] MODES = {
        "configured", "full", "incremental", "differential", "rebuild", "fixed-chunks"
    };

    private StandaloneGui() {}

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 回退到默认外观
            }
            new MainFrame().setVisible(true);
        });
    }

    /** 顶层主窗口。 */
    private static final class MainFrame extends JFrame {
        private final JTextField worldField = new JTextField(36);
        private final JTextField gameRootField = new JTextField(36);
        private final JLabel configDirLabel = new JLabel("（选择存档后自动解析 / auto-resolved）");
        private final JComboBox<String> modeCombo = new JComboBox<>(MODES);
        private final JTextField noteField = new JTextField(20);
        private final DefaultListModel<Workstation.SnapshotView> snapshotModel = new DefaultListModel<>();
        private final JList<Workstation.SnapshotView> snapshotList = new JList<>(snapshotModel);
        private final JTextArea log = new JTextArea(10, 60);

        private final JButton backupButton = new JButton("备份 / Backup");
        private final JButton refreshButton = new JButton("刷新 / Refresh");
        private final JButton statusButton = new JButton("状态 / Status");
        private final JButton detailButton = new JButton("详情 / Detail");
        private final JButton verifyButton = new JButton("校验 / Verify");
        private final JButton rollbackButton = new JButton("回滚 / Rollback");
        private final JButton deleteButton = new JButton("删除 / Delete");
        private final JButton configButton = new JButton("配置 / Config");

        MainFrame() {
            super("BaruSikoZako 离线备份工作站 / Offline Backup Workstation");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setLayout(new BorderLayout(8, 8));

            add(buildTopPanel(), BorderLayout.NORTH);
            add(buildCenterPanel(), BorderLayout.CENTER);
            add(buildLogPanel(), BorderLayout.SOUTH);

            wireActions();
            pack();
            setLocationRelativeTo(null);
        }

        private JPanel buildTopPanel() {
            var panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
            var constraints = new GridBagConstraints();
            constraints.insets = new Insets(4, 4, 4, 4);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.fill = GridBagConstraints.HORIZONTAL;

            constraints.gridx = 0;
            constraints.gridy = 0;
            panel.add(new JLabel("存档目录 / World:"), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1.0;
            panel.add(worldField, constraints);
            constraints.gridx = 2;
            constraints.weightx = 0.0;
            var browse = new JButton("浏览 / Browse…");
            browse.addActionListener(ignored -> chooseWorld());
            panel.add(browse, constraints);

            constraints.gridx = 0;
            constraints.gridy = 1;
            constraints.weightx = 0.0;
            panel.add(new JLabel("游戏根 / Game root:"), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1.0;
            panel.add(gameRootField, constraints);
            constraints.gridx = 2;
            constraints.weightx = 0.0;
            panel.add(new JLabel("（自动：saves 取上上级，否则取上级）"), constraints);

            constraints.gridx = 0;
            constraints.gridy = 2;
            constraints.weightx = 0.0;
            panel.add(new JLabel("配置目录 / Config dir:"), constraints);
            constraints.gridx = 1;
            constraints.gridwidth = 2;
            constraints.weightx = 1.0;
            panel.add(configDirLabel, constraints);
            constraints.gridwidth = 1;

            constraints.gridx = 0;
            constraints.gridy = 3;
            constraints.weightx = 0.0;
            panel.add(new JLabel("备份模式 / Mode:"), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1.0;
            var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            controls.add(modeCombo);
            controls.add(new JLabel("备注 / Note:"));
            controls.add(noteField);
            panel.add(controls, constraints);

            return panel;
        }

        private JPanel buildCenterPanel() {
            snapshotList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            snapshotList.setCellRenderer((list, value, index, selected, focused) -> {
                var label = new JLabel(value.folderName() + "  ·  " + value.layout()
                    + (value.logicalBytes().isPresent()
                        ? "  ·  " + ConfigUnits.formatBytes(value.logicalBytes().getAsLong(),
                            ConfigUnits.SizeUnit.BINARY, 1) : ""));
                if (selected) {
                    label.setOpaque(true);
                    label.setBackground(list.getSelectionBackground());
                    label.setForeground(list.getSelectionForeground());
                }
                return label;
            });

            var scroll = new JScrollPane(snapshotList);
            scroll.setBorder(BorderFactory.createTitledBorder("快照 / Snapshots"));

            var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            buttons.add(backupButton);
            buttons.add(refreshButton);
            buttons.add(statusButton);
            buttons.add(detailButton);
            buttons.add(verifyButton);
            buttons.add(rollbackButton);
            buttons.add(deleteButton);
            buttons.add(configButton);

            var panel = new JPanel(new BorderLayout());
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(buttons, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildLogPanel() {
            log.setEditable(false);
            log.setLineWrap(false);
            var scroll = new JScrollPane(log);
            scroll.setBorder(BorderFactory.createTitledBorder("日志 / Log"));
            var panel = new JPanel(new BorderLayout());
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        private void wireActions() {
            backupButton.addActionListener(ignored -> backup());
            refreshButton.addActionListener(ignored -> refresh());
            statusButton.addActionListener(ignored -> status());
            detailButton.addActionListener(ignored -> detail());
            verifyButton.addActionListener(ignored -> verify());
            rollbackButton.addActionListener(ignored -> rollback());
            deleteButton.addActionListener(ignored -> delete());
            configButton.addActionListener(ignored -> config());
            worldField.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent event) { refreshDerivedPaths(); }
            });
        }

        // --- 目录与配置 ---

        private void chooseWorld() {
            var chooser = new JFileChooser(defaultBrowseDirectory());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择存档（世界）目录");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                worldField.setText(chooser.getSelectedFile().getAbsolutePath());
                refreshDerivedPaths();
            }
        }

        /** 依据存档目录自动推导游戏根与配置目录，并回填界面。 */
        private void refreshDerivedPaths() {
            var worldText = worldField.getText().trim();
            if (worldText.isEmpty()) {
                gameRootField.setText("");
                configDirLabel.setText("（选择存档后自动解析 / auto-resolved）");
                return;
            }
            try {
                var gameRoot = Workstation.deriveGameRoot(Path.of(worldText));
                gameRootField.setText(gameRoot.toString());
                var configDir = Workstation.resolveConfigDir(gameRoot);
                boolean synced = configDir.equals(gameRoot.resolve("config").resolve("barusikozako"));
                configDirLabel.setText(configDir + (synced ? "  ·  已同步游戏配置 / synced with game config"
                    : "  ·  独立配置 / standalone"));
            } catch (Exception invalid) {
                configDirLabel.setText("（无法解析 / cannot resolve）");
            }
        }

        /** 浏览默认从入口 jar 的上级目录开始，便于定位游戏根。 */
        private static File defaultBrowseDirectory() {
            String origin = System.getProperty("barusikozako.origin.jar");
            if (origin != null && !origin.isBlank()) {
                try {
                    var dir = jarParent(Path.of(origin));
                    if (dir != null) return dir;
                } catch (Exception ignored) {
                    // 回退
                }
            }
            try {
                var location = StandaloneGui.class.getProtectionDomain().getCodeSource().getLocation();
                if (location != null && "file".equals(location.getProtocol())) {
                    var dir = jarParent(Path.of(location.toURI()));
                    if (dir != null) return dir;
                }
            } catch (Exception ignored) {
                // 回退
            }
            return new File(System.getProperty("user.dir", "."));
        }

        private static File jarParent(Path path) {
            var dir = Files.isDirectory(path) ? path : path.getParent();
            return dir != null && Files.isDirectory(dir) ? dir.toFile() : null;
        }

        private Workstation workstation() throws IOException {
            var worldText = worldField.getText().trim();
            if (worldText.isEmpty()) {
                throw new IOException("请先填写存档（世界）目录 / Please choose a world directory");
            }
            var world = Path.of(worldText);
            if (!Files.isDirectory(world)) {
                throw new IOException("存档目录不存在 / World directory does not exist: " + world);
            }
            var rootText = gameRootField.getText().trim();
            var gameRoot = rootText.isEmpty() ? Workstation.deriveGameRoot(world) : Path.of(rootText);
            return new Workstation(world, gameRoot);
        }

        // --- 操作 ---

        private void backup() {
            run("备份", progress -> {
                var workstation = workstation();
                var mode = switch ((String) modeCombo.getSelectedItem()) {
                    case "full" -> Workstation.BackupMode.FULL;
                    case "incremental" -> Workstation.BackupMode.INCREMENTAL;
                    case "differential" -> Workstation.BackupMode.DIFFERENTIAL;
                    case "rebuild" -> Workstation.BackupMode.REBUILD;
                    case "fixed-chunks" -> Workstation.BackupMode.FIXED_CHUNKS;
                    default -> Workstation.BackupMode.CONFIGURED;
                };
                var note = noteField.getText().trim();
                UUID baseline = null;
                if (mode == Workstation.BackupMode.DIFFERENTIAL || mode == Workstation.BackupMode.REBUILD) {
                    var selected = snapshotList.getSelectedValue();
                    if (selected == null) throw new IOException("差分/重建模式需要先选择一个基线快照 / select a baseline snapshot");
                    baseline = selected.id();
                }
                var snapshot = workstation.backup(mode, note.isEmpty() ? null : note, baseline, progress);
                progress.message("备份完成 / Backup complete: " + snapshot.folderName());
            });
        }

        private void refresh() {
            run("刷新列表", progress -> {
                var workstation = workstation();
                var snapshots = workstation.list();
                SwingUtilities.invokeLater(() -> {
                    snapshotModel.clear();
                    snapshots.forEach(snapshotModel::addElement);
                });
                progress.message("共 " + snapshots.size() + " 个快照 / snapshots");
            });
        }

        private void status() {
            run("状态", progress -> {
                var workstation = workstation();
                progress.message("存档 / world: " + workstation.world());
                progress.message("游戏根 / game root: " + workstation.gameRoot());
                progress.message("配置目录 / config dir: " + workstation.configDir());
                progress.message("后端 / backend: " + workstation.config().backendKeyword());
                progress.message("仓库 / repository: " + workstation.repositoryPath());
                progress.message("快照数 / snapshots: " + workstation.list().size());
            });
        }

        private void detail() {
            var snapshot = requireSelected();
            if (snapshot == null) return;
            var message = "ID: " + snapshot.id() + "\n"
                + "时间 (UTC): " + snapshot.createdAt() + "\n"
                + "目录: " + snapshot.folderName() + "\n"
                + "布局: " + snapshot.layout() + "\n"
                + "一致性: " + snapshot.consistency()
                + (snapshot.archiveBytes().isPresent() ? "\n归档: "
                    + ConfigUnits.formatBytes(snapshot.archiveBytes().getAsLong(), ConfigUnits.SizeUnit.BINARY, 1) : "")
                + (snapshot.logicalBytes().isPresent() ? "\n逻辑: "
                    + ConfigUnits.formatBytes(snapshot.logicalBytes().getAsLong(), ConfigUnits.SizeUnit.BINARY, 1) : "")
                + (snapshot.elapsed().isPresent() ? "\n耗时: " + ConfigUnits.formatDuration(snapshot.elapsed().get()) : "");
            JOptionPane.showMessageDialog(this, message, "快照详情 / Snapshot detail",
                JOptionPane.INFORMATION_MESSAGE);
        }

        private void verify() {
            var snapshot = requireSelected();
            if (snapshot == null) return;
            run("校验", progress -> {
                var result = workstation().verify(snapshot.id(), progress);
                progress.message("校验通过 / Verified: " + result.entries() + " 项 · "
                    + ConfigUnits.formatBytes(result.bytes(), ConfigUnits.SizeUnit.BINARY, 1));
            });
        }

        private void rollback() {
            var snapshot = requireSelected();
            if (snapshot == null) return;
            int answer = JOptionPane.showConfirmDialog(this,
                "回滚将用快照 " + snapshot.folderName() + " 替换当前存档，且不可逆。\n确定继续？",
                "确认回滚 / Confirm rollback", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            run("回滚", progress -> {
                var result = workstation().rollback(snapshot.id(), progress);
                progress.message("回滚 / Rollback " + result.status() + " -> " + snapshot.folderName());
                result.previousWorld().ifPresent(p -> progress.message("原存档保留在 / Previous world kept at: " + p));
            });
        }

        private void delete() {
            var snapshot = requireSelected();
            if (snapshot == null) return;
            int answer = JOptionPane.showConfirmDialog(this,
                "确认删除快照 " + snapshot.folderName() + "？", "确认删除 / Confirm delete",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            run("删除", progress -> {
                workstation().delete(snapshot.id(), progress);
                progress.message("已删除 / Deleted: " + snapshot.folderName());
            });
        }

        private void config() {
            run("读取配置", progress -> {
                var workstation = workstation();
                var server = StandaloneConfig.serverValues(workstation.configDir());
                var backend = StandaloneConfig.backendValues(workstation.configDir());
                var text = new StringBuilder();
                text.append("== server.yml ==\n");
                server.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> text.append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
                text.append("\n== ").append(workstation.config().backendKeyword()).append(".yml ==\n");
                backend.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> text.append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
                var area = new JTextArea(text.toString(), 24, 64);
                area.setEditable(false);
                JOptionPane.showMessageDialog(this, new JScrollPane(area),
                    "配置 / Configuration", JOptionPane.INFORMATION_MESSAGE);
            });
        }

        // --- 工具 ---

        private Workstation.SnapshotView requireSelected() {
            var selected = snapshotList.getSelectedValue();
            if (selected == null) {
                JOptionPane.showMessageDialog(this, "请先在列表中选择一个快照 / Select a snapshot first",
                    "提示 / Notice", JOptionPane.INFORMATION_MESSAGE);
                return null;
            }
            return selected;
        }

        /** 后台执行一次工作站操作，统一处理进度、消息与异常。 */
        private void run(String name, Operation operation) {
            setBusy(true);
            logLine("▶ 开始 " + name + " / starting " + name);
            var progress = new Workstation.Progress() {
                @Override public void message(String line) {
                    SwingUtilities.invokeLater(() -> logLine(line));
                }

                @Override public void progress(long processed, long total) {
                    // 进度已由消息驱动展示，避免刷屏
                }
            };
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    operation.run(progress);
                    return null;
                }

                @Override protected void done() {
                    setBusy(false);
                    try {
                        get();
                        logLine("✔ " + name + " 完成 / done");
                    } catch (ExecutionException failure) {
                        logLine("✖ " + name + " 失败 / failed: " + rootMessage(failure.getCause()));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        logLine("✖ " + name + " 中断 / interrupted");
                    }
                    SwingUtilities.invokeLater(() -> {
                        try {
                            var workstation = workstation();
                            var snapshots = workstation.list();
                            snapshotModel.clear();
                            snapshots.forEach(snapshotModel::addElement);
                        } catch (IOException ignored) {
                            // 刷新失败时保留旧列表
                        }
                    });
                }
            }.execute();
        }

        private void setBusy(boolean busy) {
            for (var button : List.of(backupButton, refreshButton, statusButton, detailButton,
                verifyButton, rollbackButton, deleteButton, configButton)) {
                button.setEnabled(!busy);
            }
        }

        private void logLine(String line) {
            log.append(line + System.lineSeparator());
            log.setCaretPosition(log.getDocument().getLength());
        }

        private static String rootMessage(Throwable failure) {
            var message = failure == null ? "unknown" : failure.getMessage();
            return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        }

        @FunctionalInterface
        private interface Operation {
            void run(Workstation.Progress progress) throws Exception;
        }
    }
}
