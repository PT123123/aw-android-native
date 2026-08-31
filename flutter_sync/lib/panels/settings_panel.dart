import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models.dart';
import '../sync_controller.dart';
import '../theme.dart';

/// 「设置」面板：2 开关 + 1 下拉 + 3 数字输入 + 2 复选（同步目标）+ 别名 + 保存 + 危险区
class SettingsPanel extends StatefulWidget {
  const SettingsPanel({super.key, required this.controller});

  final SyncController controller;

  @override
  State<SettingsPanel> createState() => _SettingsPanelState();
}

class _SettingsPanelState extends State<SettingsPanel> {
  final _formKey = GlobalKey<FormState>();
  final _udpPortCtl = TextEditingController();
  final _probeIntervalCtl = TextEditingController();
  final _aliasCtl = TextEditingController();

  bool _enabled = false;
  bool _httpEnabled = true;
  String _discoveryMethod = 'broadcast';
  int _listenPort = 5600;
  bool _syncInbox = true;
  bool _syncActivity = true;
  bool _saving = false;
  bool _hydrated = false;

  SyncController get c => widget.controller;

  void _hydrate(SyncConfig cfg) {
    _enabled = cfg.enabled;
    _httpEnabled = cfg.httpEnabled;
    _discoveryMethod = cfg.discoveryMethod;
    _listenPort = cfg.listenPort;
    _syncInbox = cfg.syncInbox;
    _syncActivity = cfg.syncActivity;
    _udpPortCtl.text = cfg.udpPort.toString();
    _probeIntervalCtl.text = cfg.probeInterval.toString();
    _aliasCtl.text = cfg.selfAlias;
    _hydrated = true;
  }

  @override
  void dispose() {
    _udpPortCtl.dispose();
    _probeIntervalCtl.dispose();
    _aliasCtl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _saving = true);
    final cfg = SyncConfig(
      enabled: _enabled,
      httpEnabled: _httpEnabled,
      discoveryMethod: _discoveryMethod,
      listenPort: _listenPort,
      udpPort: int.parse(_udpPortCtl.text),
      syncInbox: _syncInbox,
      syncActivity: _syncActivity,
      selfAlias: _aliasCtl.text.trim(),
      probeInterval: int.parse(_probeIntervalCtl.text),
    );
    final err = await c.saveConfig(cfg);
    if (!mounted) return;
    setState(() => _saving = false);
    final messenger = ScaffoldMessenger.of(context);
    if (err != null) {
      messenger.showSnackBar(SnackBar(content: Text('保存失败: $err')));
      return;
    }
    messenger.showSnackBar(SnackBar(
      content: Text(c.config?.enabled == true
          ? '同步设置已保存：局域网同步已开启，UDP 广播发现已启动（同网段设备将自动互相发现）'
          : '同步设置已保存（局域网同步处于关闭状态）'),
    ));
  }

  Future<void> _clearAll() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空所有配对信息'),
        content: const Text(
          '确定要清空所有配对信息吗？\n'
          '此操作将移除全部已配对与已发现的设备，且不可恢复。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: SyncColors.danger),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确定清空'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    final (cleared, err) = await c.clearAllDevices();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(err != null
          ? '清空失败：$err'
          : '已清空所有配对信息，共移除 $cleared 台设备'),
    ));
  }

  Widget _label(String text) => Padding(
        padding: const EdgeInsets.only(top: 14, bottom: 6),
        child: Text(text,
            style: const TextStyle(fontWeight: FontWeight.w600)),
      );

  Widget _hint(String text) => Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text(text,
            style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: .5))),
      );

  @override
  Widget build(BuildContext context) {
    final cfg = c.config;
    if (cfg == null) {
      return const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (!_hydrated) _hydrate(cfg);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
      child: Form(
        key: _formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('开启局域网同步'),
              subtitle: const Text('局域网同步'),
              value: _enabled,
              onChanged: (v) => setState(() => _enabled = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('使用 HTTP 同步'),
              subtitle: const Text('HTTP 同步'),
              value: _httpEnabled,
              onChanged: (v) => setState(() => _httpEnabled = v),
            ),
            _label('设备发现方式'),
            DropdownButtonFormField<String>(
              initialValue: _discoveryMethod,
              dropdownColor: SyncColors.field,
              isExpanded: true,
              items: [
                const DropdownMenuItem(
                  value: 'broadcast',
                  child: Text('广播 / mDNS + UDP（已实现）'),
                ),
                const DropdownMenuItem(
                  value: 'poll',
                  child: Text('轮询遍历（待实现）'),
                ),
                // 服务端若返回未列出的方式（如 mdns），补一个条目避免下拉断言
                if (_discoveryMethod != 'broadcast' &&
                    _discoveryMethod != 'poll')
                  DropdownMenuItem(
                    value: _discoveryMethod,
                    child: Text(_discoveryMethod),
                  ),
              ],
              onChanged: (v) =>
                  setState(() => _discoveryMethod = v ?? 'broadcast'),
            ),
            _hint('已实现「广播 / mDNS+UDP」自动发现；「轮询遍历」功能待后续迭代。'),
            _label('同步端口 (HTTP)'),
            TextFormField(
              initialValue: _listenPort.toString(),
              enabled: false,
              decoration: const InputDecoration(),
            ),
            _hint('设备间同步 HTTP 端口，与服务器端口一致（固定 5600）。'),
            _label('UDP 发现端口'),
            TextFormField(
              controller: _udpPortCtl,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              validator: (v) {
                final n = int.tryParse(v ?? '');
                if (n == null || n < 10000 || n > 65535) {
                  return '端口需在 10000 ~ 65535 之间';
                }
                return null;
              },
            ),
            _hint('广播 / mDNS+UDP 自动发现固定端口（默认 46000）。'),
            _label('在线探测间隔 (秒)'),
            TextFormField(
              controller: _probeIntervalCtl,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              validator: (v) {
                final n = int.tryParse(v ?? '');
                if (n == null || n < 2 || n > 3600) {
                  return '间隔需在 2 ~ 3600 秒之间';
                }
                return null;
              },
            ),
            _hint('已配对设备定时间隔探测在线状态（默认 10s）。'),
            _label('同步目标'),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('Inbox 数据 (inbox.db)'),
              value: _syncInbox,
              onChanged: (v) => setState(() => _syncInbox = v ?? false),
            ),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('ActivityWatch 数据 (sqlite.db)'),
              value: _syncActivity,
              onChanged: (v) => setState(() => _syncActivity = v ?? false),
            ),
            _label('本机别名'),
            TextFormField(
              controller: _aliasCtl,
              maxLength: 20,
              decoration: const InputDecoration(
                hintText: '设置本机别名（为空则使用主机名）',
              ),
            ),
            _hint('广播时显示在对方设备列表中。'),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _saving ? null : _save,
              icon: _saving
                  ? const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.save_outlined, size: 18),
              label: const Text('保存同步设置'),
            ),
            const Divider(height: 32),
            _label('配对数据'),
            OutlinedButton(
              style: OutlinedButton.styleFrom(
                foregroundColor: SyncColors.danger,
                side: const BorderSide(color: SyncColors.danger),
              ),
              onPressed: _clearAll,
              child: const Text('清空所有配对信息'),
            ),
            _hint('移除全部已配对 / 已发现的设备（不可恢复）'),
          ],
        ),
      ),
    );
  }
}
