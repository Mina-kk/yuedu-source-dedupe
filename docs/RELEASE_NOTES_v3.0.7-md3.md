# 3.0.7-md3 发布说明

基线：3.0.6-md3（versionCode 308，含 Switch 功能卡、快速准确预设、同域淘汰收紧）。

## book / 上下文变量

- URL 模板支持 `{{book.name}}`、`{{book.author}}`（URL 编码后填入）。
- 支持 `{{book.bookUrl}}`、`{{book.tocUrl}}`、`{{book.origin}}`、`{{baseUrl}}`、`{{source.bookSourceUrl}}`、`{{source.key}}`（原样填入）。
- 变量随校验链路实时更新：搜索关键词 → 书籍详情页 → 目录页。
- 链路尚无值时回退：`book.name` 用搜索关键词，`book.origin` / `baseUrl` / `source.*` 用书源地址。
- 花括号两侧空格（`{{ book.name }}`）与单花括号写法均可识别。

## 常见模板表达式

- `{{ key }}`、`{{ page }}` 带空格写法。
- 纯算术，如 `{{(page-1)*20}}`：用内置小解析器求值，不执行脚本；除零或非法表达式保持原样。
- `{{java.encodeURI(key)}}`、`{{java.encodeURI(key,"gbk")}}`、`encodeURIComponent(key)`、`encodeURI(key)`。

## 未解析模板降级

- 替换后仍残留 `{{...}}` / `{xxx}` 的请求不再发出，该步记「未覆盖」，归入「待复核」。
- 不计失败、不参与淘汰，避免把模板原样请求后误判失效。

## 目录地址

- 详情页 `tocUrl` 只有像地址（`http(s)://`、单个 `/` 开头或含 `{{`）时才拼成 URL。
- `.read@href`、`//a/@href` 这类提取规则不再被误拼成地址。

## 测试与 APK

```text
JVM 测试：201 项通过，0 失败，0 错误
包名：com.mina.yuedu
versionCode：309
versionName：3.0.7-md3
APK：阅读书源去重-v3.0.7-md3.apk
大小：5,550,463 字节
SHA-256：dd04c1cf72228313568aaf5b2501be10ab3bc4ade98228948d0480cfc5f9ab0d
签名证书 SHA-1：4b8fda609b4a959f0b93937b14ef42ead81fae54
```

## 当前限制

- `{{book.name+'x'}}`、`{{java.encodeURI(book.name)}}` 等含拼接或嵌套变量的复杂表达式仍记「未覆盖」。
- 书源校验尚未实现与阅读 WebBook 完全一致的 Rhino JavaScript、CSS 和 XPath 规则执行。复杂动态规则可能出现误判。
