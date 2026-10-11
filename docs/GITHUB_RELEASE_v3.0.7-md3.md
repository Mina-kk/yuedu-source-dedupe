# 阅读书源去重 3.0.7-md3

本版本让校验支持 `book` / 上下文变量与常见模板表达式，无法求值的模板记「未覆盖」而不判失败，减少误删。

## 主要更新

- 支持 `{{book.name}}`、`{{book.author}}`、`{{book.bookUrl}}`、`{{book.tocUrl}}`、`{{book.origin}}`、`{{baseUrl}}`、`{{source.bookSourceUrl}}`、`{{source.key}}`，按校验链路实时填入。
- 带空格的 `{{ key }}` / `{{ page }}`、纯算术 `{{(page-1)*20}}`、`java.encodeURI(key[,"gbk"])` / `encodeURIComponent(key)` 就地求值，不执行脚本。
- 仍解析不了的模板不发请求，记「未覆盖 / 待复核」，不计失败。
- 详情页 `tocUrl` 为 `.read@href` 等提取规则时不再被拼成地址。

## 下载与校验

```text
APK：阅读书源去重-v3.0.7-md3.apk
大小：5,550,463 字节
SHA-256：dd04c1cf72228313568aaf5b2501be10ab3bc4ade98228948d0480cfc5f9ab0d
签名证书 SHA-1：4b8fda609b4a959f0b93937b14ef42ead81fae54
```

## 当前限制

含拼接或嵌套变量的复杂表达式（如 `{{book.name+'x'}}`）仍记「未覆盖」。书源校验尚未实现与阅读 WebBook 完全一致的 Rhino JavaScript、CSS 和 XPath 规则执行。
