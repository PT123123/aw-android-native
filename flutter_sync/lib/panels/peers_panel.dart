import 'package:flutter/material.dart';

import '../format.dart';
import '../models.dart';
import '../sync_controller.dart';
import '../theme.dart';

/// 「配对与设备」面板：发现状态条、本机地址、已发现列表、已配对列表
/// （立即同步/删除/重命名）、同步摘要与展开详情。
class PeersPanel extends StatelessWidget {
  const PeersPanel({super.key, required this.controller});

  final SyncController controller;

  SyncController get c => controller;

  @override
  Widget build(BuildContext context) {
    final status = c.status;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _DiscoveryBanner(running: status?.discoveryRunning ?? false, status: status),
          const SizedBox(height: 12),
          _SelfAddress(device: c.selfDevice),
          const Divider(height: 24),
          const _SectionTitle('已发现未配对的设备'),
          if (c.discoveredDevices.isEmpty)
            const _Muted(
                '未发现设备 —— 确保双方已开启「局域网同步」并处于同一网络。')
          else
            for (final d in c.discoveredDevices)
              _DiscoveredRow(key: ValueKey(d.id), controller: c, device: d),
          const Divider(height: 24),
          const _SectionTitle('已配对设备'),
          if (c.pairedDevices.isEmpty)
            const _Muted('尚无已配对设备。')
          else
            for (final d in c.pairedDevices)
              _PairedBlock(key: ValueKey(d.id), controller: c, device: d),
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(text,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
      );
}

class _Muted extends StatelessWidget {
  const _Muted(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Text(text, style: TextStyle(color: Colors.white.withValues(alpha: .55))),
      );
}

class _DiscoveryBanner extends StatelessWidget {
  const _DiscoveryBanner({required this.running, required this.status});

  final bool running;
  final DiscoveryStatus? status;

  @override
  Widget build(BuildContext context) {
    final color = running ? SyncColors.success : SyncColors.warning;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: color.withValues(alpha: .25),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              running ? '● 运行中' : '○ 未开启',
              style: TextStyle(color: color, fontSize: 12),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              running
                  ? 'UDP 广播发现运行中，同网段设备将自动互相发现'
                  '（UDP ${status?.udpPort ?? '-'} / HTTP ${status?.listenPort ?? '-'}）'
                  : '局域网同步未开启 —— 请先在下方「设置」中打开开关并保存',
              style: const TextStyle(fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

class _SelfAddress extends StatelessWidget {
  const _SelfAddress({required this.device});

  final Device? device;

  @override
  Widget build(BuildContext context) {
    final d = device;
    final missing = d == null || d.ip.isEmpty || isLoopback(d.ip);
    return Row(
      children: [
        const Text('本机地址：'),
        if (missing)
          const Expanded(
            child: Text(
              '未获取到局域网 IP（请检查 Wi-Fi 连接）',
              style: TextStyle(color: SyncColors.warning, fontSize: 13),
            ),
          )
        else ...[
          Text('${d.ip}:${d.port}',
              style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              '(${d.ipIface ?? '未知网卡'}) (ID: ${d.id.isEmpty ? '-' : d.id})',
              style: TextStyle(
                  fontSize: 12, color: Colors.white.withValues(alpha: .55)),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ],
    );
  }
}

class _OnlineBadge extends StatelessWidget {
  const _OnlineBadge({required this.online});

  final bool online;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(left: 6),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: (online ? SyncColors.success : Colors.grey).withValues(alpha: .2),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          online ? '在线' : '离线',
          style: TextStyle(
              fontSize: 11, color: online ? SyncColors.success : Colors.grey),
        ),
      );
}

/// 已发现未配对设备行：发起配对 / 接受配对
class _DiscoveredRow extends StatelessWidget {
  const _DiscoveredRow(
      {super.key, required this.controller, required this.device});

  final SyncController controller;
  final Device device;

  Future<void> _pair(BuildContext context) async {
    final err = device.incomingPairRequest
        ? await controller.acceptPair(device.id)
        : await controller.initiatePair(device.id);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(err != null
          ? '配对失败：$err'
          : (device.incomingPairRequest ? '已接受配对' : '已发起配对请求，等待对方确认')),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final online = device.isEffectivelyOnline;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(device.displayName,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                Row(children: [
                  Expanded(
                    child: Text(
                      '${device.ip}:${device.port} · ${deviceTypeLabel(device.deviceKind)}',
                      style: TextStyle(
                          fontSize: 12, color: Colors.white.withValues(alpha: .6)),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  _OnlineBadge(online: online),
                ]),
              ],
            ),
          ),
          const SizedBox(width: 8),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor:
                  device.incomingPairRequest ? SyncColors.success : null,
              padding: const EdgeInsets.symmetric(horizontal: 12),
            ),
            onPressed: () => _pair(context),
            child: Text(device.incomingPairRequest ? '接受配对' : '发起配对'),
          ),
        ],
      ),
    );
  }
}

/// 已配对设备块：设备行 + 同步摘要 + 展开详情
class _PairedBlock extends StatelessWidget {
  const _PairedBlock({super.key, required this.controller, required this.device});

  final SyncController controller;
  final Device device;

  @override
  Widget build(BuildContext context) {
    final stats = controller.deviceStats[device.id];
    final expanded = controller.expandedDevices.contains(device.id);
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: .03),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: SyncColors.border, width: 0.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _PairedRow(controller: controller, device: device),
          if (stats != null && !device.isSelf) ...[
            const SizedBox(height: 8),
            _SyncSummary(
              stats: stats,
              expanded: expanded,
              onToggle: () => controller.toggleDeviceDetails(device.id),
            ),
            if (expanded)
              _SyncDetails(
                stats: stats,
                conflicts: controller.deviceConflicts[device.id] ?? const [],
              ),
          ],
        ],
      ),
    );
  }
}

class _PairedRow extends StatefulWidget {
  const _PairedRow({required this.controller, required this.device});

  final SyncController controller;
  final Device device;

  @override
  State<_PairedRow> createState() => _PairedRowState();
}

class _PairedRowState extends State<_PairedRow> {
  bool _renaming = false;
  final _aliasCtl = TextEditingController();
  bool _busy = false;

  SyncController get c => widget.controller;
  Device get d => widget.device;

  @override
  void dispose() {
    _aliasCtl.dispose();
    super.dispose();
  }

  void _startRename() {
    _aliasCtl.text = d.alias ?? d.name;
    setState(() => _renaming = true);
  }

  Future<void> _saveRename() async {
    final alias = _aliasCtl.text.trim();
    if (alias.isEmpty || alias == (d.alias ?? d.name)) {
      setState(() => _renaming = false);
      return;
    }
    setState(() => _busy = true);
    final err = await c.saveAlias(d.id, alias);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _renaming = false;
    });
    if (err != null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('修改别名失败：$err')));
    }
  }

  Future<void> _sync() async {
    setState(() => _busy = true);
    final (applied, err) = await c.syncDevice(d.id);
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(err != null ? '同步失败：$err' : '同步完成，应用记录数 $applied'),
    ));
  }

  Future<void> _remove() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除设备'),
        content: Text('确定删除该设备（${d.displayName}）？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: SyncColors.danger),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final err = await c.removeDevice(d.id);
    if (!mounted) return;
    if (err != null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('删除失败：$err')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final online = d.isEffectivelyOnline;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    if (d.isSelf)
                      const Padding(
                        padding: EdgeInsets.only(right: 4),
                        child: Text('(本机)',
                            style: TextStyle(color: SyncColors.link)),
                      ),
                    Expanded(
                      child: Text(d.displayName,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                          overflow: TextOverflow.ellipsis),
                    ),
                  ]),
                  const SizedBox(height: 2),
                  Row(children: [
                    Expanded(
                      child: Text(
                        'ID: ${d.id} | ${d.ip}:${d.port} · ${deviceTypeLabel(d.deviceKind)}',
                        style: TextStyle(
                            fontSize: 12, color: Colors.white.withValues(alpha: .55)),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    _OnlineBadge(online: online),
                  ]),
                  if (d.lastSyncAt != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        '上次同步: ${formatDateTime(d.lastSyncAt)}',
                        style: TextStyle(
                            fontSize: 12, color: Colors.white.withValues(alpha: .55)),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_renaming)
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _aliasCtl,
                  decoration: const InputDecoration(hintText: '新别名'),
                ),
              ),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: _busy ? null : _saveRename,
                child: const Text('确定'),
              ),
              const SizedBox(width: 6),
              OutlinedButton(
                onPressed: () => setState(() => _renaming = false),
                child: const Text('取消'),
              ),
            ],
          )
        else
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              if (!d.isSelf)
                OutlinedButton.icon(
                  onPressed: _busy ? null : _sync,
                  icon: const Icon(Icons.sync, size: 16),
                  label: const Text('立即同步'),
                ),
              if (!d.isSelf)
                OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: SyncColors.danger,
                    side: const BorderSide(color: SyncColors.danger),
                  ),
                  onPressed: _busy ? null : _remove,
                  child: const Text('删除'),
                ),
              OutlinedButton(
                onPressed: d.isSelf ? null : _startRename,
                child: const Text('重命名'),
              ),
            ],
          ),
      ],
    );
  }
}

class _SyncSummary extends StatelessWidget {
  const _SyncSummary({
    required this.stats,
    required this.expanded,
    required this.onToggle,
  });

  final DeviceSyncStats stats;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    Widget stat(String text) => Text(text,
        style: TextStyle(fontSize: 12.5, color: Colors.white.withValues(alpha: .75)));
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: SyncColors.border, width: 0.5)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Wrap(
              spacing: 14,
              runSpacing: 4,
              children: [
                stat('待同步: ${stats.pendingPushCount} 条'),
                stat('冲突: ${stats.pendingConflictCount} 条'),
                stat('总同步: ${stats.totalSyncedCount} 条'),
                stat(humanSize(stats.totalSyncedSize)),
              ],
            ),
          ),
          OutlinedButton(
            onPressed: onToggle,
            child: Text(expanded ? '收起详情' : '展开详情'),
          ),
        ],
      ),
    );
  }
}

class _SyncDetails extends StatelessWidget {
  const _SyncDetails({required this.stats, required this.conflicts});

  final DeviceSyncStats stats;
  final List<ConflictSummary> conflicts;

  String get _dataDiff {
    final diff = stats.localNoteCount - stats.remoteNoteCount;
    if (diff > 0) return '本地多 $diff 条';
    if (diff < 0) return '远端多 ${-diff} 条';
    return '数据一致';
  }

  Widget _section(String title, List<Widget> children) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                style:
                    const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            ...children,
          ],
        ),
      );

  Widget _li(String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 1.5),
        child: Text(text,
            style: TextStyle(fontSize: 12.5, color: Colors.white.withValues(alpha: .85))),
      );

  @override
  Widget build(BuildContext context) {
    return AnimatedSize(
      duration: const Duration(milliseconds: 200),
      alignment: Alignment.topLeft,
      child: Container(
        margin: const EdgeInsets.only(top: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: .04),
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: SyncColors.border, width: 0.5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _section('数据统计', [
              _li('本地数据: ${stats.localNoteCount} 条'),
              _li('远端数据: ${stats.remoteNoteCount} 条'),
              _li('数据差异: $_dataDiff'),
            ]),
            _section('时间信息', [
              _li('上次同步: ${stats.lastSyncAt ?? '从未同步'}'),
              _li('上次全量同步: ${stats.lastFullSyncAt ?? '从未全量同步'}'),
              _li('同步频率: ${syncFrequencyText(stats.syncFrequencyMinutes)}'),
            ]),
            if (conflicts.isNotEmpty)
              _section('冲突列表 (${conflicts.length} 条)', [
                for (final cf in conflicts)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 5, vertical: 1.5),
                          decoration: BoxDecoration(
                            color: (cf.resolved
                                    ? SyncColors.success
                                    : SyncColors.warning)
                                .withValues(alpha: .2),
                            borderRadius: BorderRadius.circular(3),
                          ),
                          child: Text(
                            cf.resolved ? '[已解决]' : '[待解决]',
                            style: TextStyle(
                              fontSize: 11,
                              color: cf.resolved
                                  ? SyncColors.success
                                  : SyncColors.warning,
                            ),
                          ),
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            '${cf.noteTitle} - ${formatTime(cf.detectedAt)}',
                            style: const TextStyle(fontSize: 12.5),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  ),
              ]),
            _section('最近错误', [
              if (stats.lastError == null || stats.lastError!.isEmpty)
                Text('无错误记录',
                    style: TextStyle(
                        fontSize: 12.5, color: Colors.white.withValues(alpha: .5)))
              else ...[
                Text(stats.lastError!,
                    style:
                        const TextStyle(fontSize: 12.5, color: SyncColors.danger)),
                if (stats.lastErrorAt != null)
                  Text(formatTime(stats.lastErrorAt),
                      style: TextStyle(
                          fontSize: 11.5, color: Colors.white.withValues(alpha: .5))),
              ],
            ]),
          ],
        ),
      ),
    );
  }
}
