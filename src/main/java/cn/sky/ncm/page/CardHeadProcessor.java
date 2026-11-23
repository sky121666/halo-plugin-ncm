package cn.sky.ncm.page;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.AttributeValueQuotes;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import run.halo.app.theme.router.ModelConst;

/**
 * 在文章页和独立页面的 head 里加入文章插歌卡片的样式和脚本（card.css、card.js）。
 * 卡片在文章里存成普通链接（{@code <div class="ncm-card" data-ncm-card data-type data-id><a>标题</a></div>}），
 * card.js 请求 /music/card/{type}/{id} 渲染预览，播放链接进入 Folia。
 * 只按页面类型（Halo 的 _templateId）判断，和插件设置无关。
 */
@Component
public class CardHeadProcessor implements TemplateHeadProcessor {

    /** 文章、独立页面的 _templateId。Halo 2.27 里文章是 post，独立页面是 page；其余写法是为兼容主题或旧版本留的。 */
    static final Set<String> PAGE_TYPES = Set.of("post", "page", "single-page", "single_page", "singlePage",
        "singlepage");

    static final String CARD_URL = "/music/card/";
    static final String ASSETS = "/plugins/plugin-ncm/assets/static/";

    private final String version;

    public CardHeadProcessor(PluginContext pluginContext) {
        this.version = Objects.requireNonNullElse(pluginContext.getVersion(), "0");
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler handler) {
        if (!shouldInject(Objects.toString(context.getVariable(ModelConst.TEMPLATE_ID), ""))) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> append(context.getModelFactory(), model));
    }

    static boolean shouldInject(String templateId) {
        return templateId != null && PAGE_TYPES.contains(templateId.trim());
    }

    void append(IModelFactory f, IModel model) {
        String v = "?v=" + version;
        model.add(f.createStandaloneElementTag("link", linkAttributes(v), AttributeValueQuotes.DOUBLE, false, true));
        model.add(f.createOpenElementTag("script", scriptAttributes(v), AttributeValueQuotes.DOUBLE, false));
        model.add(f.createCloseElementTag("script"));
    }

    static Map<String, String> linkAttributes(String v) {
        Map<String, String> link = new LinkedHashMap<>();
        link.put("rel", "stylesheet");
        link.put("href", ASSETS + "card.css" + v);
        link.put("data-ncm-card-css", "");
        return link;
    }

    static Map<String, String> scriptAttributes(String v) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("src", ASSETS + "card.js" + v);
        a.put("defer", "");
        a.put("data-ncm-card-js", "");
        a.put("data-endpoint", CARD_URL);
        return a;
    }
}
