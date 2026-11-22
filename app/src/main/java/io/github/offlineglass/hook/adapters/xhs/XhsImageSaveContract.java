package io.github.offlineglass.hook.adapters.xhs;

import java.util.List;
import java.util.Properties;

import static io.github.offlineglass.hook.adapters.xhs.XhsNativeContract.role;
import io.github.offlineglass.hook.adapters.xhs.XhsNativeContract.Body;
import io.github.offlineglass.hook.adapters.xhs.XhsNativeContract.Field;
import io.github.offlineglass.hook.adapters.xhs.XhsNativeContract.Ref;

/** Contracts for the photo gallery menu and its native static-image save action. */
final class XhsImageSaveContract {
    private static final String GALLERY =
        "Lcom/xingin/matrix/notedetail/imagecontent/imagegallery/AsyncImageGalleryController";
    private static final String CONFIG = "Lcom/xingin/entities/MediaSaveConfig;";
    private static final String IMAGE = "Lcom/xingin/entities/ImageBean;";
    private static final String NOTE = "Lcom/xingin/entities/notedetail/NoteFeed;";
    private static final String FUNCTION_SWITCH = "Lcom/xingin/entities/notedetail/FunctionSwitch;";
    private static final String BOXED_BOOLEAN = "Ljava/lang/Boolean;";
    private static final String ACTIVITY = "Landroid/app/Activity;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String ACTION = "FeedbackDownloadImage(fieldId=";
    private static final String SAVE = "imageSave start, saveStaticImage=";
    private static final String URL = ", filePath=";
    private static final String LIVE = ", livePhotoVideoPath=";
    private static final String SAVE_PROXY = "Landroid/xingin/com/spi/saveimage/ISaveImageProxy";

    static boolean isActionLabel(String value) { return value.equals(URL) || value.equals(LIVE); }

    static boolean inspect(Ref ref) {
        return inGallery(ref) || ref.owner().equals(CONFIG) || ref.owner().equals(IMAGE) ||
            isDownloadMenuSignature(ref) ||
            ref.name().equals("invoke") && ref.args().equals(List.of("Ljava/lang/Object;")) ||
            ref.name().equals("toString") ||
            ref.result().equals("V") && (ref.args().equals(List.of("Z")) ||
                ref.args().size() == 3 && ref.args().get(2).equals("Z"));
    }

    static boolean retain(Body body) {
        return inGallery(body.ref) || body.ref.owner().equals(CONFIG) || body.ref.owner().equals(IMAGE) ||
            isDownloadMenuDecorator(body) || body.ref.args().isEmpty() && body.ref.result().equals(BOXED_BOOLEAN) &&
                body.reads.stream().anyMatch(XhsImageSaveContract::isMenuDisabledFlag) || isPhotoClick(body) ||
            body.strings.contains(ACTION) || body.strings.contains(SAVE) ||
            body.ref.args().equals(List.of("Z")) && body.writes.stream().anyMatch(XhsImageSaveContract::isSaveFlag);
    }

    private static boolean inGallery(Ref ref) {
        return ref.owner().equals(GALLERY + ";") || ref.owner().startsWith(GALLERY + "$");
    }

    private static boolean isSaveFlag(Field field) {
        return field.type().equals("Z") && field.name().equals("disableSaveMedia");
    }

    private static boolean isDownloadMenuSignature(Ref ref) {
        return ref.args().equals(List.of(NOTE, ref.result(), "Z")) && ref.result().startsWith("L");
    }

    private static boolean isDownloadMenuDecorator(Body body) {
        return isDownloadMenuSignature(body.ref) &&
            body.reads.stream().anyMatch(field -> field.name().equals("IMAGE_DOWNLOAD")) &&
            body.calls.stream().anyMatch(call -> call.owner().equals(FUNCTION_SWITCH) &&
                call.result().equals("Z") && call.args().isEmpty());
    }

    private static boolean isMenuDisabledFlag(Field field) {
        return field.name().equals("disable") && field.type().equals(BOXED_BOOLEAN);
    }

    private static boolean isPhotoClick(Body body) {
        return body.strings.contains("note_detail") &&
            body.calls.stream().anyMatch(call -> call.owner().startsWith(SAVE_PROXY + "$")) ||
            body.strings.contains("DownloadController") &&
            body.strings.stream().anyMatch(value -> value.startsWith("download blocked, disableSaveMedia=true"));
    }

    static void bind(List<Body> all, Properties properties) {
        var decorators = all.stream().filter(XhsImageSaveContract::isDownloadMenuDecorator).toList();
        role(properties, "photoDownloadMenu", decorators.stream().map(body -> body.ref.key()).toList());
        if (decorators.size() == 1) {
            String model = decorators.get(0).ref.result();
            role(properties, "photoDownloadMenuDisabled", all.stream().filter(body ->
                body.ref.owner().equals(model) && body.ref.result().equals(BOXED_BOOLEAN) && body.ref.args().isEmpty())
                .flatMap(body -> body.reads.stream()).filter(field -> field.owner().equals(model) && isMenuDisabledFlag(field))
                .map(Field::key).toList());
        }
        var menu = all.stream().filter(body -> body.ref.owner().equals(GALLERY + ";") &&
            body.calls.stream().anyMatch(call -> call.result().equals(CONFIG)) &&
            body.calls.stream().anyMatch(call -> call.args().equals(List.of(IMAGE)))).toList();
        var setters = all.stream().filter(body -> menu.stream().anyMatch(caller -> caller.calls.contains(body.ref)) &&
            body.writes.stream().anyMatch(field -> field.owner().equals(body.ref.owner()) && isSaveFlag(field)) &&
            body.ref.args().equals(List.of("Z")) && (body.flags & 8) == 0).toList();
        role(properties, "photoSaveFlag", setters.stream().map(body -> body.ref.key()).toList());
        if (setters.size() == 1) role(properties, "photoSaveMenu", menu.stream()
            .filter(body -> body.calls.contains(setters.get(0).ref)).map(body -> body.ref.key()).toList());
        var imageMethods = all.stream().filter(body -> body.ref.owner().equals(IMAGE) &&
            body.ref.args().isEmpty() && body.ref.result().equals(STRING)).toList();
        var largeUrl = imageMethods.stream().filter(body -> readsImageField(body, "url_size_large")).toList();
        role(properties, "photoSelectedUrl", imageMethods.stream().filter(body ->
            readsImageField(body, "translateUrl") && largeUrl.stream().anyMatch(large -> body.calls.contains(large.ref)) &&
            menu.stream().anyMatch(caller -> caller.calls.contains(body.ref))).map(body -> body.ref.key()).toList());
        role(properties, "photoOriginalUrl", imageMethods.stream().filter(body ->
            readsImageField(body, "original") && readsImageField(body, "translateUrl") &&
            body.calls.stream().anyMatch(call -> call.name().equals("areEqual") &&
                call.owner().equals("Lkotlin/jvm/internal/Intrinsics;"))).map(body -> body.ref.key()).toList());

        var actions = all.stream().filter(body -> body.ref.name().equals("toString") &&
            body.strings.contains(ACTION)).toList();
        if (actions.size() != 1) return;
        Body action = actions.get(0);
        var clicks = all.stream().filter(body -> isPhotoClick(body) && body.calls.stream()
            .anyMatch(call -> call.owner().equals(action.ref.owner()) && call.name().equals("<init>"))).toList();
        properties.setProperty("photoClicks", String.join("\n", clicks.stream().map(body -> body.ref.key()).distinct().toList()));
        role(properties, "photoClickFlag", all.stream().filter(body -> body.ref.owner().equals(CONFIG) &&
            body.ref.result().equals("Z") && body.ref.args().isEmpty() &&
            body.reads.stream().anyMatch(XhsImageSaveContract::isSaveFlag) &&
            clicks.stream().anyMatch(click -> click.calls.contains(body.ref))).map(body -> body.ref.key()).toList());
        var helpers = all.stream().filter(body -> body.strings.contains("ImageSave") && body.strings.contains(SAVE) &&
            body.ref.args().size() == 3 && body.ref.args().get(1).equals(action.ref.owner()) &&
            (body.flags & 8) == 0 && all.stream().anyMatch(caller -> inGallery(caller.ref) &&
                caller.strings.contains("FeedbackDownloadImage disableWaterMark=") && caller.calls.contains(body.ref))).toList();
        role(properties, "photoSave", helpers.stream().map(body -> body.ref.key()).toList());
        if (helpers.size() != 1) return;
        Body helper = helpers.get(0);
        role(properties, "photoActivity", helper.reads.stream()
            .filter(field -> field.owner().equals(helper.ref.owner()) && field.type().equals(ACTIVITY))
            .map(Field::key).toList());
        bindActionField(properties, action, helper, URL, "photoUrl");
        bindActionField(properties, action, helper, LIVE, "photoLiveUrl");
    }

    private static boolean readsImageField(Body body, String name) {
        return body.reads.stream().anyMatch(field -> field.owner().equals(IMAGE) &&
            field.name().equals(name) && field.type().equals(STRING));
    }

    private static void bindActionField(Properties properties, Body action, Body helper, String label, String name) {
        role(properties, name, action.labelledReads.getOrDefault(label, List.of()).stream()
            .filter(field -> field.type().equals(STRING) && helper.reads.contains(field)).map(Field::key).toList());
    }
}
