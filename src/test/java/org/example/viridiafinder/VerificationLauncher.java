package org.example.viridiafinder;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import java.nio.file.Path;

/** 使用 Fabric 正常的类加载及访问修正；不启动窗口、不连接服务器。 */
public final class VerificationLauncher {
    public static void main(String[] args) throws Exception {
        var knot=new Knot(EnvType.CLIENT);
        var loader=knot.init(new String[]{"--gameDir","."});
        for(String path:args) knot.addToClassPath(Path.of(path));
        loader.loadClass("org.example.viridiafinder.SurveyTest").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
    }
}
