package ai.hermes.agent

import android.app.Application

/**
 * 3.0：这里曾经有一个每 2 秒遍历整棵 View 树、再往 WebView 里打一次
 * evaluateJavascript 的「兜底注入」轮询。它在主线程上周期性递归 + 触发样式
 * 重算，是 2.0「卡卡的」的直接来源之一。
 *
 * 现在主屏是纯原生 View，WebView 只在进入工作台时加载，注入由
 * WebViewClient.onPageFinished 一次性完成即可：style 是 document 级节点，SPA
 * 前端路由切换不会移除它，整页跳转会再次触发 onPageFinished。轮询没有必要。
 *
 * 保留这个 Application 子类是为了不改动 AndroidManifest 的注册。
 */
class HermesApp : Application()
