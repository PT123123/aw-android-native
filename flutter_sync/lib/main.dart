import 'package:flutter/material.dart';

import 'panels/logs_panel.dart';
import 'panels/peers_panel.dart';
import 'panels/settings_panel.dart';
import 'sync_controller.dart';
import 'theme.dart';

/// 模块入口：独立运行（flutter run）与宿主 FlutterFragment 的默认路由 "/" 共用。
void main() {
  runApp(const SyncApp());
}

class SyncApp extends StatelessWidget {
  const SyncApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '局域网同步',
      debugShowCheckedModeBanner: false,
      theme: buildSyncTheme(),
      home: const SyncPage(),
    );
  }
}

class SyncPage extends StatefulWidget {
  const SyncPage({super.key, this.controller});

  /// 可选注入（测试用）；缺省自建并连接 127.0.0.1:5600。
  final SyncController? controller;

  @override
  State<SyncPage> createState() => _SyncPageState();
}

class _SyncPageState extends State<SyncPage> {
  late final SyncController controller = widget.controller ?? SyncController();
  late final bool _ownsController = widget.controller == null;

  @override
  void initState() {
    super.initState();
    controller.addListener(_onChange);
    controller.start();
  }

  void _onChange() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    controller.removeListener(_onChange);
    // 离开页面取消全部轮询（对应 vue 的 beforeDestroy）；
    // 外部注入的控制器由注入方负责释放
    if (_ownsController) controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('局域网同步'),
        backgroundColor: SyncColors.card,
        foregroundColor: Colors.white,
      ),
      body: controller.initialLoading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(12),
              children: [
                const Text(
                  '在本地网络内与其他设备同步 Inbox 与 ActivityWatch 数据。',
                  style: TextStyle(fontSize: 13, color: Colors.white70),
                ),
                const SizedBox(height: 12),
                _SyncExpansionTile(
                  title: '配对与设备',
                  initiallyExpanded: true,
                  child: PeersPanel(controller: controller),
                ),
                const SizedBox(height: 12),
                _SyncExpansionTile(
                  title: '设置',
                  child: SettingsPanel(controller: controller),
                ),
                const SizedBox(height: 12),
                _SyncExpansionTile(
                  title: '显示报文',
                  child: LogsPanel(controller: controller),
                ),
              ],
            ),
    );
  }
}

class _SyncExpansionTile extends StatefulWidget {
  const _SyncExpansionTile({
    required this.title,
    required this.child,
    this.initiallyExpanded = false,
  });

  final String title;
  final Widget child;
  final bool initiallyExpanded;

  @override
  State<_SyncExpansionTile> createState() => _SyncExpansionTileState();
}

class _SyncExpansionTileState extends State<_SyncExpansionTile> {
  late bool _open = widget.initiallyExpanded;

  @override
  Widget build(BuildContext context) {
    return ExpansionTile(
      initiallyExpanded: widget.initiallyExpanded,
      maintainState: true,
      tilePadding: const EdgeInsets.symmetric(horizontal: 12),
      onExpansionChanged: (v) => setState(() => _open = v),
      leading: Icon(
        _open ? Icons.expand_more : Icons.chevron_right,
        size: 20,
        color: Colors.white70,
      ),
      title: Text(widget.title,
          style: const TextStyle(fontWeight: FontWeight.w600)),
      children: [widget.child],
    );
  }
}
