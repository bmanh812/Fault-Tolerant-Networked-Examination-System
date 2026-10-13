package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.ClientExamState;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SwingExamFrame extends JFrame implements ExamUiView {
    private static final String JOIN_CARD = "join";
    private static final String EXAM_CARD = "exam";
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField candidateField;
    private final JTextField examField;
    private final JButton joinButton = new JButton("Tham gia thi");
    private final JLabel joinErrorLabel = new JLabel(" ");
    private final JLabel connectionLabel = new JLabel(ConnectionDisplayStatus.DISCONNECTED.label());
    private final JLabel examTitleLabel = new JLabel();
    private final JLabel examMetaLabel = new JLabel();
    private final JLabel timerLabel = new JLabel("Thời gian còn lại: sẽ được đồng bộ ở Phase 7");
    private final JPanel questionsPanel = new JPanel();
    private final JButton submitButton = new JButton("Nộp bài");
    private final Map<String, QuestionWidgets> questionWidgets = new LinkedHashMap<>();
    private final Timer connectionPoll;
    private final ExamCountdownTimer countdownTimer;

    private String displayedDeadline = "";

    private ExamUiController controller;

    public SwingExamFrame(
            String defaultHost,
            int defaultPort,
            String defaultCandidateId,
            String defaultExamId
    ) {
        super("Networked MCQ Examination System");
        hostField = new JTextField(defaultHost, 20);
        portField = new JTextField(String.valueOf(defaultPort), 8);
        candidateField = new JTextField(defaultCandidateId, 20);
        examField = new JTextField(defaultExamId, 20);
        connectionPoll = new Timer(500, event -> {
            if (controller != null) {
                controller.refreshConnectionState();
            }
        });
        countdownTimer = new ExamCountdownTimer(
                new ExamCountdown(),
                new SwingCountdownTicker(500),
                this::renderCountdown
        );

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 620));
        setSize(980, 720);
        setLocationRelativeTo(null);
        cards.add(createJoinPanel(), JOIN_CARD);
        cards.add(createExamPanel(), EXAM_CARD);
        setContentPane(cards);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
            }
        });
    }

    public void bind(ExamUiController controller) {
        if (this.controller != null) {
            throw new IllegalStateException("Controller is already bound");
        }
        this.controller = controller;
        joinButton.addActionListener(event -> controller.join(
                hostField.getText(),
                portField.getText(),
                candidateField.getText(),
                examField.getText()
        ));
        connectionPoll.start();
    }

    @Override
    public void showJoinInProgress() {
        joinButton.setEnabled(false);
        joinErrorLabel.setText("Đang gửi yêu cầu tham gia kỳ thi...");
        showConnectionState(ConnectionDisplayStatus.CONNECTING);
    }

    @Override
    public void showJoinError(String message) {
        joinButton.setEnabled(true);
        joinErrorLabel.setText(message);
        joinErrorLabel.setForeground(new Color(180, 35, 35));
        cardLayout.show(cards, JOIN_CARD);
    }

    @Override
    public void showExam(ClientExamState.Snapshot exam) {
        examTitleLabel.setText(exam.title());
        examMetaLabel.setText(
                "Mã kỳ thi: " + exam.examId()
                        + "    |    Phiên: " + exam.sessionId()
                        + "    |    Trạng thái: " + exam.sessionStatus()
        );
        syncCountdown(exam.serverTime(), exam.deadline());
        rebuildQuestions(exam);
        cardLayout.show(cards, EXAM_CARD);
    }

    @Override
    public void syncCountdown(long serverTimeEpochMillis, long deadlineEpochMillis) {
        displayedDeadline = deadlineEpochMillis > 0
                ? DEADLINE_FORMAT.format(Instant.ofEpochMilli(deadlineEpochMillis))
                : "không khả dụng";
        countdownTimer.sync(serverTimeEpochMillis, deadlineEpochMillis);
    }

    @Override
    public void stopCountdown() {
        countdownTimer.stop();
    }

    @Override
    public void showAnswerState(
            String questionId,
            AnswerDraft answer,
            AnswerDisplayStatus status
    ) {
        QuestionWidgets widgets = questionWidgets.get(questionId);
        if (widgets == null) {
            return;
        }
        if (answer.selectedOptionId() != null) {
            JRadioButton selected = widgets.options().get(answer.selectedOptionId());
            if (selected != null) {
                selected.setSelected(true);
            }
        }
        updateStatus(widgets.statusLabel(), status);
    }

    @Override
    public void showAnswerStatus(String questionId, AnswerDisplayStatus status) {
        QuestionWidgets widgets = questionWidgets.get(questionId);
        if (widgets != null) {
            updateStatus(widgets.statusLabel(), status);
        }
    }

    @Override
    public void showConnectionState(ConnectionDisplayStatus status) {
        connectionLabel.setText(status.label());
        connectionLabel.setForeground(status == ConnectionDisplayStatus.CONNECTED
                ? new Color(28, 122, 65)
                : new Color(180, 35, 35));
    }

    @Override
    public void setAnsweringEnabled(boolean enabled) {
        for (QuestionWidgets widgets : questionWidgets.values()) {
            for (JRadioButton option : widgets.options().values()) {
                option.setEnabled(enabled);
            }
        }
    }

    private JPanel createJoinPanel() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBorder(new EmptyBorder(32, 32, 32, 32));
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Tham gia kỳ thi"),
                new EmptyBorder(18, 18, 18, 18)
        ));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(7, 7, 7, 7);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        JLabel heading = new JLabel("Networked MCQ Examination System");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        addRow(form, constraints, 0, "Máy chủ", hostField);
        addRow(form, constraints, 1, "Cổng", portField);
        addRow(form, constraints, 2, "Mã thí sinh", candidateField);
        addRow(form, constraints, 3, "Mã kỳ thi", examField);

        constraints.gridx = 0;
        constraints.gridy = 4;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.CENTER;
        form.add(joinButton, constraints);
        constraints.gridy = 5;
        joinErrorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        form.add(joinErrorLabel, constraints);

        constraints.gridy = 0;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.anchor = GridBagConstraints.CENTER;
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.add(heading, BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        outer.add(content, constraints);
        return outer;
    }

    private JPanel createExamPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(new EmptyBorder(14, 18, 14, 18));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        examTitleLabel.setFont(examTitleLabel.getFont().deriveFont(Font.BOLD, 20f));
        header.add(examTitleLabel);
        header.add(Box.createVerticalStrut(5));
        header.add(examMetaLabel);
        header.add(Box.createVerticalStrut(5));
        header.add(timerLabel);
        header.add(Box.createVerticalStrut(5));
        header.add(connectionLabel);
        panel.add(header, BorderLayout.NORTH);

        questionsPanel.setLayout(new BoxLayout(questionsPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(questionsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        panel.add(scroll, BorderLayout.CENTER);

        submitButton.setEnabled(false);
        submitButton.setToolTipText("Luồng nộp bài sẽ được triển khai ở Phase 8");
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(new JLabel("Nộp bài được khóa cho tới Phase 8."), BorderLayout.WEST);
        footer.add(submitButton, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void rebuildQuestions(ClientExamState.Snapshot exam) {
        questionsPanel.removeAll();
        questionWidgets.clear();
        for (ClientExamState.Question question : exam.questions()) {
            AnswerDraft answer = exam.answers().get(question.questionId());
            questionsPanel.add(createQuestionPanel(question, answer));
            questionsPanel.add(Box.createVerticalStrut(10));
        }
        questionsPanel.revalidate();
        questionsPanel.repaint();
    }

    private JPanel createQuestionPanel(ClientExamState.Question question, AnswerDraft answer) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Câu " + question.position()),
                new EmptyBorder(8, 12, 10, 12)
        ));
        panel.setAlignmentX(LEFT_ALIGNMENT);

        JTextArea questionText = new JTextArea(question.text());
        questionText.setEditable(false);
        questionText.setOpaque(false);
        questionText.setLineWrap(true);
        questionText.setWrapStyleWord(true);
        questionText.setFont(questionText.getFont().deriveFont(Font.BOLD));
        questionText.setFocusable(false);
        panel.add(questionText);
        panel.add(Box.createVerticalStrut(7));

        ButtonGroup group = new ButtonGroup();
        Map<String, JRadioButton> options = new LinkedHashMap<>();
        for (ClientExamState.Option option : question.options()) {
            JRadioButton button = new JRadioButton(option.label() + ". " + option.text());
            button.setActionCommand(option.optionId());
            if (answer != null && option.optionId().equals(answer.selectedOptionId())) {
                button.setSelected(true);
            }
            button.addActionListener(event -> {
                if (controller != null) {
                    controller.selectAnswer(question.questionId(), option.optionId());
                }
            });
            group.add(button);
            options.put(option.optionId(), button);
            panel.add(button);
        }

        JLabel status = new JLabel();
        AnswerDisplayStatus initial = answer == null
                ? AnswerDisplayStatus.UNANSWERED
                : AnswerDisplayStatus.from(answer, null, false);
        updateStatus(status, initial);
        panel.add(Box.createVerticalStrut(5));
        panel.add(status);
        questionWidgets.put(question.questionId(), new QuestionWidgets(options, status));
        return panel;
    }

    private void closeWindow() {
        connectionPoll.stop();
        stopCountdown();
        setEnabled(false);
        ExamUiController activeController = controller;
        Thread closer = new Thread(() -> {
            if (activeController != null) {
                activeController.close();
            }
            SwingUtilities.invokeLater(this::dispose);
        }, "exam-ui-close");
        closer.setDaemon(true);
        closer.start();
    }

    private void renderCountdown(ExamCountdownTimer.Display display) {
        timerLabel.setText(switch (display.state()) {
            case RUNNING -> "Thời gian còn lại: " + display.text()
                    + "    |    Deadline: " + displayedDeadline;
            case EXPIRED_WAITING_FOR_SERVER -> "Thời gian còn lại: 00:00"
                    + "    |    Hết thời gian - đang chờ xác nhận từ Server";
            case UNAVAILABLE -> "Thời gian còn lại: không khả dụng";
        });
    }

    private static void updateStatus(JLabel label, AnswerDisplayStatus status) {
        label.setText(status.label());
        label.setForeground(switch (status) {
            case SAVED -> new Color(28, 122, 65);
            case SAVING -> new Color(176, 112, 0);
            case SYNC_ERROR -> new Color(180, 35, 35);
            case UNANSWERED -> Color.DARK_GRAY;
        });
    }

    private static void addRow(
            JPanel panel,
            GridBagConstraints constraints,
            int row,
            String label,
            JTextField field
    ) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(label + ":"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(field, constraints);
    }

    private record QuestionWidgets(Map<String, JRadioButton> options, JLabel statusLabel) {
    }
}
