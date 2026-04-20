package com.github.claudecodegui.taskstate;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeBalloonNotifier;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.notifications.SystemReminderNotifier;
import com.github.claudecodegui.util.SoundNotificationService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 灏嗙粺涓€浠诲姟鐘舵€佸垎鍙戝埌涓嶅悓鎻愰啋娓犻亾銆?
 * 杩欓噷鐨勬牳蹇冭亴璐ｄ笉鏄€滅淮鎶ょ姸鎬佲€濓紝鑰屾槸鏍规嵁绛栫暐鎶婂悓涓€涓?snapshot
 * 鍚屾璺敱鍒?popup銆乥alloon銆乻tatus bar銆乻ound 鍥涚鍑哄彛銆?
 */
public class TaskReminderDispatcher {

    private static final Logger LOG = Logger.getInstance(TaskReminderDispatcher.class);
    private static final int MAX_DEDUP_CACHE_SIZE = 256;

    @FunctionalInterface
    public interface ReminderSoundPlayer {
        void play(TaskState state);
    }

    @FunctionalInterface
    public interface IdeFocusChecker {
        boolean isIdeFocused();
    }

    @FunctionalInterface
    public interface ReminderMessageResolver {
        String resolve(TaskStateSnapshot snapshot);
    }

    private final HandlerContext context;
    private final Supplier<TaskReminderPolicy> policySupplier;
    private final ClaudeBalloonNotifier balloonNotifier;
    private final SystemReminderNotifier systemReminderNotifier;
    private final ReminderSoundPlayer reminderSoundPlayer;
    private final IdeFocusChecker ideFocusChecker;
    private final ReminderMessageResolver reminderMessageResolver;
    private final Gson gson = new Gson();
    // popup 鍜?balloon 鍒嗗埆缁存姢鍘婚噸缂撳瓨锛岄伩鍏嶄竴娆＄姸鎬佸彉鏇村湪 React 閲嶆寕杞姐€?
    // session 鎭㈠鎴栭噸澶嶆秷鎭帹閫佹椂澶氭寮瑰嚭鐩稿悓鎻愰啋銆?
    private final Map<String, Boolean> popupDedupKeys = new LinkedHashMap<>();
    private final Map<String, Boolean> balloonDedupKeys = new LinkedHashMap<>();
    private final Map<String, Boolean> systemDedupKeys = new LinkedHashMap<>();

    /**
     * 浣跨敤榛樿绛栫暐鏋勫缓鎻愰啋鍒嗗彂鍣ㄣ€?
     * 鐢熶骇鐜榛樿浼氭妸瀹屾垚鎬佸０闊炽€両DE 鐒︾偣鍒ゆ柇銆佹皵娉℃彁閱掔瓑鐪熷疄渚濊禆鍏ㄩ儴鎺ヤ笂銆?
     */
    public TaskReminderDispatcher(HandlerContext context) {
        this(
            context,
            () -> TaskReminderPolicy.defaults(),
            new ClaudeBalloonNotifier(),
            new SystemReminderNotifier(),
            SoundNotificationService.getInstance()::playTaskReminderSound,
            () -> ApplicationManager.getApplication().isActive(),
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 渚涗笟鍔′唬鐮佹敞鍏ヨ嚜瀹氫箟绛栫暐鍜屾皵娉″疄鐜扮殑鏋勯€犳柟娉曘€?
     * 澹伴煶鎾斁浠嶇劧澶嶇敤 {@link SoundNotificationService} 鐨?task reminder 鍏ュ彛銆?
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        SoundNotificationService soundNotificationService
    ) {
        this(
            context,
            () -> policy,
            balloonNotifier,
            new SystemReminderNotifier(),
            soundNotificationService::playTaskReminderSound,
            () -> ApplicationManager.getApplication().isActive(),
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 渚涗笟鍔′唬鐮佹寜娆¤В鏋愭渶鏂扮瓥鐣ョ殑鏋勯€犳柟娉曘€?     */
    public TaskReminderDispatcher(
        HandlerContext context,
        Supplier<TaskReminderPolicy> policySupplier,
        ClaudeBalloonNotifier balloonNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker
    ) {
        this(
            context,
            policySupplier,
            balloonNotifier,
            new SystemReminderNotifier(),
            reminderSoundPlayer,
            ideFocusChecker,
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 支持按次解析策略并注入 system notifier 的构造器。
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        Supplier<TaskReminderPolicy> policySupplier,
        ClaudeBalloonNotifier balloonNotifier,
        SystemReminderNotifier systemReminderNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker
    ) {
        this(
            context,
            policySupplier,
            balloonNotifier,
            systemReminderNotifier,
            reminderSoundPlayer,
            ideFocusChecker,
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 渚涘崟鍏冩祴璇曟垨鏇寸粏绮掑害瀹氬埗浣跨敤鐨勫畬鏁存瀯閫犳柟娉曘€?     * 杩欓噷鎶娾€滃０闊虫挱鏀锯€濆拰鈥淚DE 鏄惁鑱氱劍鈥濋兘鎶芥垚鍑芥暟鎺ュ彛锛屼究浜庣ǔ瀹氶獙璇佸垎鍙戠瓥鐣ャ€?     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker
    ) {
        this(
            context,
            () -> policy,
            balloonNotifier,
            new SystemReminderNotifier(),
            reminderSoundPlayer,
            ideFocusChecker,
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 兼容旧调用方：未显式传入 system notifier 时使用默认实现。
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker,
        ReminderMessageResolver reminderMessageResolver
    ) {
        this(
            context,
            () -> policy,
            balloonNotifier,
            new SystemReminderNotifier(),
            reminderSoundPlayer,
            ideFocusChecker,
            reminderMessageResolver
        );
    }

    /**
     * 鍏煎鐩存帴娉ㄥ叆鍥哄畾绛栫暐涓旇嚜瀹氫箟鏂囨瑙ｆ瀽鍣ㄧ殑鍦烘櫙銆?     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        SystemReminderNotifier systemReminderNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker
    ) {
        this(
            context,
            () -> policy,
            balloonNotifier,
            systemReminderNotifier,
            reminderSoundPlayer,
            ideFocusChecker,
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 支持自定义文案解析器与 system notifier 的完整构造器。
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        SystemReminderNotifier systemReminderNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker,
        ReminderMessageResolver reminderMessageResolver
    ) {
        this(
            context,
            () -> policy,
            balloonNotifier,
            systemReminderNotifier,
            reminderSoundPlayer,
            ideFocusChecker,
            reminderMessageResolver
        );
    }

    /**
     * 瀹屾暣鏋勯€犳柟娉曪紝鍏佽娴嬭瘯鎴栦笂灞傚畾鍒舵彁閱掓枃妗堟潵婧愩€?     * 杩欐牱涓氬姟浠ｇ爜浠嶉粯璁よ蛋 bundle锛屾湰鍦版祴璇曞垯鍙互绋冲畾娉ㄥ叆浼炕璇戝櫒銆?     */
    public TaskReminderDispatcher(
        HandlerContext context,
        Supplier<TaskReminderPolicy> policySupplier,
        ClaudeBalloonNotifier balloonNotifier,
        SystemReminderNotifier systemReminderNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker,
        ReminderMessageResolver reminderMessageResolver
    ) {
        this.context = context;
        this.policySupplier = policySupplier;
        this.balloonNotifier = balloonNotifier;
        this.systemReminderNotifier = systemReminderNotifier;
        this.reminderSoundPlayer = reminderSoundPlayer;
        this.ideFocusChecker = ideFocusChecker;
        this.reminderMessageResolver = reminderMessageResolver;
    }

    /**
     * 鏍规嵁褰撳墠浠诲姟蹇収鍚戝涓彁閱掓笭閬撳垎鍙戦€氱煡銆?
     *
     * <p>澶勭悊椤哄簭閬靛惊鈥滅姸鎬佹爮 -> 姘旀场 -> 澹伴煶 -> 鍓嶇寮圭獥鈥濈殑鎬濊矾锛?
     * 鐘舵€佹爮鏈€杞汇€佹渶绋冲畾锛沺opup 鏈€鎵撴柇鐢ㄦ埛锛屽洜姝ゆ渶鍚庡啀鍒ゆ柇涓斿甫鍘婚噸銆?
     *
     * @param snapshot 褰撳墠鑱氬悎鍚庣殑浠诲姟鐘舵€佸揩鐓?
     * @param approvalDialogOpen 褰撳墠鏄惁宸茬粡鏈夊鎵瑰脊绐楁墦寮€锛岀敤浜庢姂鍒堕噸澶?popup
     */
    public void dispatch(TaskStateSnapshot snapshot, boolean approvalDialogVisible) {
        if (snapshot == null || snapshot.getState() == null || context == null) {
            return;
        }
        boolean ideFocused = ideFocusChecker.isIdeFocused();
        TaskReminderPolicy policy = resolvePolicy();
        TaskReminderPolicy.ReminderDecision decision = policy.decide(snapshot, approvalDialogVisible, ideFocused);
        String reminderMessage = reminderMessageResolver.resolve(snapshot);
        String dedupKey = buildDedupKey(snapshot);
        boolean shouldShowBalloon = decision.shouldShowBalloon();
        boolean shouldShowPopup = decision.shouldShowPopup();
        boolean shouldShowSystem = decision.shouldShowSystem();
        boolean balloonDispatched = shouldShowBalloon && markDispatched(balloonDedupKeys, dedupKey);
        boolean popupDispatched = shouldShowPopup && markDispatched(popupDedupKeys, dedupKey);
        boolean systemDispatched = shouldShowSystem && markDispatched(systemDedupKeys, dedupKey);

        LOG.debug(
            "[TaskReminderDispatcher] state=" + snapshot.getState().getValue()
                + ", ideFocused=" + ideFocused
                + ", approvalDialogVisible=" + approvalDialogVisible
                + ", popup=" + shouldShowPopup + "/" + popupDispatched + "/" + decision.getPopupReason()
                + ", balloon=" + shouldShowBalloon + "/" + balloonDispatched + "/" + decision.getBalloonReason()
                + ", sound=" + decision.shouldPlaySound() + "/" + decision.getSoundReason()
                + ", system=" + shouldShowSystem + "/" + systemDispatched + "/" + decision.getSystemReason()
                + ", statusBar=" + decision.shouldUpdateStatusBar()
        );

        if (decision.shouldUpdateStatusBar()) {
            Project project = context.getProject();
            if (project != null && !project.isDisposed()) {
                ClaudeNotifier.showTaskReminderStatus(project, snapshot.getState(), reminderMessage);
            }
        }

        if (balloonDispatched) {
            balloonNotifier.showTaskReminder(context.getProject(), snapshot.getState(), reminderMessage);
        }

        if (decision.shouldPlaySound()) {
            reminderSoundPlayer.play(snapshot.getState());
        }

        if (systemDispatched && systemReminderNotifier != null) {
            systemReminderNotifier.showTaskReminder(context.getProject(), snapshot.getState(), reminderMessage);
        }

        if (popupDispatched) {
            dispatchPopup(snapshot, reminderMessage);
        }
    }

    /**
     * 从设置页主动触发一次 popup 自检，复用与真实提醒一致的前端弹窗链路。
     */
    public void dispatchTestPopup() {
        dispatchTestPopup(ClaudeCodeGuiBundle.message("task.reminder.preview.popup"));
    }

    /**
     * 允许测试用例注入自定义文案，便于验证 popup 预览走的是同一条桥接链路。
     */
    public void dispatchTestPopup(String message) {
        TaskStateSnapshot snapshot = buildPreviewSnapshot(TaskState.WAITING_CONFIRM, "preview_popup");
        LOG.info("[TaskReminderDispatcher] Dispatching popup reminder preview to webview overlay");
        dispatchPopup(snapshot, message);
    }

    /**
     * 从设置页主动触发一次 balloon 自检，便于区分“插件未发送”和“IDE 设置不展示”。
     */
    public void dispatchTestBalloon() {
        dispatchTestBalloon(ClaudeCodeGuiBundle.message("task.reminder.preview.balloon"));
    }

    /**
     * 允许测试用例注入自定义文案，验证 balloon 预览是否真实发送到了 IDE 通知组。
     */
    public void dispatchTestBalloon(String message) {
        Project project = context != null ? context.getProject() : null;
        if (project == null || project.isDisposed()) {
            LOG.warn("[TaskReminderDispatcher] Skip balloon reminder preview because project is unavailable");
            return;
        }
        balloonNotifier.showTaskReminder(project, TaskState.COMPLETED, message);
        LOG.info("[TaskReminderDispatcher] Sent balloon reminder preview to IDE notification group; if no popup appears, check IDE Notifications settings");
    }

    /**
     * 鍚戝墠绔彂閫?task reminder popup 璇锋眰銆?
     * 濡傛灉 React 鍥炶皟灏氭湭娉ㄥ唽锛屽垯鍏堝啓鍏?window 渚х紦瀛橀槦鍒楋紝绛夊緟鍓嶇鍒濆鍖栧悗鍥炴斁銆?
     */
    private void dispatchPopup(TaskStateSnapshot snapshot, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("state", snapshot.getState().getValue());
        payload.addProperty("message", message);
        if (hasText(snapshot.getSessionId())) {
            payload.addProperty("sessionId", snapshot.getSessionId());
        }
        if (hasText(snapshot.getRequestId())) {
            payload.addProperty("requestId", snapshot.getRequestId());
        }

        String escapedPayload = context.escapeJs(gson.toJson(payload));
        // popup 鍙兘鏃╀簬 React App 瀹屾垚鍒濆鍖栵紝姝ゆ椂涓嶈兘鐩存帴涓㈠純璇锋眰銆?
        // 鍏堟妸 payload 鏆傚瓨鍦?window.__pendingTaskReminderDialogRequests锛?
        // 寰呭墠绔寕涓?showTaskReminderDialog 鍥炶皟鍚庡啀缁熶竴鍥炴斁銆?
        String jsCode = "(function(){"
            + "var payload='" + escapedPayload + "';"
            + "if (window.showTaskReminderDialog) {"
            + "  window.showTaskReminderDialog(payload);"
            + "} else {"
            + "  window.__pendingTaskReminderDialogRequests = window.__pendingTaskReminderDialogRequests || [];"
            + "  window.__pendingTaskReminderDialogRequests.push(payload);"
            + "}"
            + "})();";
        context.executeJavaScriptOnEDT(jsCode);
    }

    private TaskReminderPolicy resolvePolicy() {
        TaskReminderPolicy policy = policySupplier != null ? policySupplier.get() : null;
        return policy != null ? policy : TaskReminderPolicy.defaults();
    }

    private TaskStateSnapshot buildPreviewSnapshot(TaskState state, String reason) {
        long now = System.currentTimeMillis();
        String requestId = "preview-" + now;
        return new TaskStateSnapshot(
            state,
            null,
            requestId,
            new TaskStateEvent(state, null, requestId, reason, now)
        );
    }

    /**
     * 鏍规嵁鐘舵€佸拰鏈€杩戜竴娆′簨浠跺師鍥犵敓鎴愭彁閱掓枃妗堛€?
     * 杩欓噷缁熶竴鏀舵暃鏂囨锛岄伩鍏嶇姸鎬佹爮銆佹皵娉″拰 popup 鍚勮嚜鎷兼帴涓嶅悓鍐呭銆?
     */
    private static String buildDefaultReminderMessage(TaskStateSnapshot snapshot) {
        String reason = snapshot.getLatestEvent() != null ? snapshot.getLatestEvent().getReason() : null;

        return switch (snapshot.getState()) {
            case WAITING_CONFIRM -> ClaudeCodeGuiBundle.message("task.reminder.waitingConfirm");
            case FINAL_ERROR -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.finalError");
            case COMPLETED -> ClaudeCodeGuiBundle.message("task.reminder.completed");
            case RECOVERED -> ClaudeCodeGuiBundle.message("task.reminder.recovered");
            case RETRYING -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.retrying");
            case CANCELLED -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.cancelled");
            case RUNNING -> ClaudeCodeGuiBundle.message("task.reminder.running");
            case PENDING -> ClaudeCodeGuiBundle.message("task.reminder.pending");
        };
    }

    /**
     * 涓轰竴娆℃彁閱掔敓鎴愮ǔ瀹氱殑鍘婚噸閿€?
     * 鍙鐘舵€併€乻ession/request 浠ュ強鏈€杩戜簨浠舵椂闂存埑鐩稿悓锛屽氨瑙嗕负鍚屼竴娆℃彁閱掋€?
     */
    private String buildDedupKey(TaskStateSnapshot snapshot) {
        String sessionId = snapshot.getSessionId() != null ? snapshot.getSessionId() : "";
        String requestId = snapshot.getRequestId() != null ? snapshot.getRequestId() : "";
        long eventTimestamp = snapshot.getLatestEvent() != null ? snapshot.getLatestEvent().getTimestamp() : 0L;
        // 浣跨敤鈥滅姸鎬?+ session/request + 鏈€鏂颁簨浠舵椂闂存埑鈥濈粍鍚堝幓閲嶏紝
        // 鏃㈣兘鎸′綇鍚屼竴浜嬩欢鐨勯噸澶嶆姇閫掞紝涔熶笉浼氭妸涓嬩竴娆＄湡瀹炵姸鎬佸彉鍖栬鍒ゆ垚閲嶅銆?
        return snapshot.getState().name() + "|" + sessionId + "|" + requestId + "|" + eventTimestamp;
    }

    /**
     * 灏濊瘯鎶婃彁閱掗敭鍐欏叆鍘婚噸缂撳瓨銆?
     *
     * @return true 琛ㄧず杩欐槸棣栨鍒嗗彂锛沠alse 琛ㄧず宸插垎鍙戣繃锛屽簲璺宠繃
     */
    private boolean markDispatched(Map<String, Boolean> dedupMap, String key) {
        synchronized (dedupMap) {
            if (dedupMap.containsKey(key)) {
                return false;
            }
            dedupMap.put(key, Boolean.TRUE);
            trimDedupMap(dedupMap);
            return true;
        }
    }

    /**
     * 淇壀鍘婚噸缂撳瓨锛岄伩鍏嶉暱鏃堕棿杩愯鍚庡唴瀛樻寔缁闀裤€?
     * 绛栫暐涓婁紭鍏堜繚鐣欐渶杩戠殑鎻愰啋璁板綍锛屽洜涓哄畠浠渶鍙兘鍐嶆琚噸澶嶆姇閫掋€?
     */
    private void trimDedupMap(Map<String, Boolean> dedupMap) {
        while (dedupMap.size() > MAX_DEDUP_CACHE_SIZE) {
            // LinkedHashMap 鎸夋彃鍏ラ『搴忕Щ闄ゆ渶鏃ч」锛?
            // 杩欐牱鍘婚噸缂撳瓨涓嶄細鏃犻檺澧為暱锛屼絾鏈€杩戜竴鎵规彁閱掍粛鐒跺彲鎷︽埅閲嶅寮瑰嚭銆?
            Iterator<String> iterator = dedupMap.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 鍒ゆ柇瀛楃涓叉槸鍚﹀寘鍚湁鏁堟枃鏈€?
     * 杩欓噷涓昏鐢ㄤ簬鍐冲畾鏄惁鎶?sessionId / requestId 鍐欒繘鍓嶇 payload銆?
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
