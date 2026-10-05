package com.mina.yuedu.core;

import com.mina.yuedu.check.CaptchaInspect;
import com.mina.yuedu.check.CheckResultIndex;
import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Filters the export/import list. Does not mutate retained in-memory records. */
public final class ExportFilter {
  private ExportFilter() {}

  public static List<SourceRecord> apply(
      List<SourceRecord> retained,
      List<CheckSourceResult> checks,
      boolean cleanLogin,
      boolean onlyUsable,
      boolean deleteCaptcha,
      Set<SourceKind> allow) {
    List<SourceRecord> out = new ArrayList<SourceRecord>();
    if (retained == null) return out;
    if (allow != null && allow.isEmpty()) return out;
    // 校验证据统一走 CheckResultIndex：实例身份 + URL+名称+完整 raw 唯一指纹，
    // 绝不按裸 URL 匹配——同 URL 规则/字段不同的源不互相套用校验结果。
    CheckResultIndex index = CheckResultIndex.of(checks);
    boolean hasChecks = checks != null && !checks.isEmpty();
    for (SourceRecord source : retained) {
      if (source == null) continue;
      CheckSourceResult check = index.find(source);
      // 需要登录：仅实测证据删除——LoginDetector 命中 loginUi/loginUrl 表单。
      // 静态字段 loginUrl/loginCheckUrl 只作提示不删除：generic FAILED（网络超时/解析失败）
      // 不等于需要登录，不能与字段声明叠加成删除依据。
      if (cleanLogin && LoginDetector.needsLogin(source)) continue;
      // 弹窗验证码：仅实测证据删除——CaptchaInspect 命中弹窗验证码分组。
      // 静态字段 captchaUrl/verifyCode/authUrl 只作提示：generic FAILED 不等于验证码拦截。
      if (deleteCaptcha && hasChecks && CaptchaInspect.hasCaptchaGroup(check)) continue;
      // "仅探测通过"开关：只留实测 SUCCESS 的源——无证据、FAILED、TIMEOUT、SKIPPED(待复核)
      // 都不满足"探测通过"这个显式条件。待复核源不参与同域淘汰竞争，本身并未被删除；
      // 用户不勾此开关即保留导出。
      if (onlyUsable && hasChecks && (check == null || !check.isUsable())) continue;
      if (allow != null && !allow.contains(SourceKind.of(source))) continue;
      out.add(source);
    }
    return out;
  }

  public static int captchaCount(List<CheckSourceResult> checks) {
    int n = 0;
    if (checks == null) return 0;
    for (CheckSourceResult r : checks) if (CaptchaInspect.hasCaptchaGroup(r)) n++;
    return n;
  }
}
