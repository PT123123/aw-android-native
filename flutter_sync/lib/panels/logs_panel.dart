import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../format.dart';
import '../models.dart';
import '../sync_controller.dart';
import '../theme.dart';

/// 「显示报文」面板：5 个筛选器 + 刷新/清空 + 8 列表格 + 空态
class LogsPanel extends StatefulWidget {
  const LogsPanel({super.key, required this.controller});

  final SyncController controller;

  @override
  State<LogsPanel> createState() => _LogsPanelState();
}

class _LogsPanelState extends State<LogsPanel> {
  late final TextEditingController _intervalCtl =
      TextEditingController(text: widget.controller.refreshSeconds.toString());

  SyncController get c => widget.controller;

  @override
  void dispose() {
    _intervalCtl.dispose();
    super.dispose();
  }

  void _applyInterval(String v) {
    final n = int.tryParse(v);
    if (n == null) return;
    c.setRefreshSeconds(n);
    _intervalCtl.text = c.refreshSeconds.toString();
  }

  Future<void> _clearLogs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空报文日志'),
        content: const Text(
          '确定要清空所有报文日志吗？\n'
          '此操作仅清除「显示报文」里的调试记录，不影响设备与配对信息。',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: SyncColors.danger),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确定清空'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final err = await c.clearLogs();
    if (!mounted) return;
    if (err != null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('清空日志失败：$err')));
    }
  }

  Widget _dropdown({
    required String label,
    required String value,
    required List<DropdownMenuItem<String>> items,
    required ValueChanged<String?> onChanged,
  }) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style:
                  TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: .6))),
          const SizedBox(height: 4),
          DropdownButtonFormField<String>(
            initialValue: value,
            isExpanded: true,
            dropdownColor: SyncColors.field,
            items: items,
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    const allItem = DropdownMenuItem(value: '', child: Text('全部'));
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('定时刷新(秒)',
                        style: TextStyle(
                            fontSize: 12,
                            color: Colors.white.withValues(alpha: .6))),
                    const SizedBox(height: 4),
                    TextField(
                      controller: _intervalCtl,
                      keyboardType: TextInputType.number,
                      inputFormatters: [
                        FilteringTextInputFormatter.digitsOnly,
                      ],
                      decoration: const InputDecoration(),
                      onChanged: _applyInterval,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('显示条数',
                        style: TextStyle(
                            fontSize: 12,
                            color: Colors.white.withValues(alpha: .6))),
                    const SizedBox(height: 4),
                    DropdownButtonFormField<String>(
                      initialValue: c.pageSize.toString(),
                      isExpanded: true,
                      dropdownColor: SyncColors.field,
                      items: const [
                        DropdownMenuItem(value: '5', child: Text('5')),
                        DropdownMenuItem(value: '10', child: Text('10')),
                        DropdownMenuItem(value: '50', child: Text('50')),
                      ],
                      onChanged: (v) {
                        final n = int.tryParse(v ?? '');
                        if (n == null) return;
                        setState(() => c.pageSize = n);
                        c.loadLogs();
                      },
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              _dropdown(
                label: '来向/去向',
                value: c.filterDirection,
                items: const [
                  allItem,
                  DropdownMenuItem(value: 'out', child: Text('去向（发出）')),
                  DropdownMenuItem(value: 'in', child: Text('来向（接收）')),
                ],
                onChanged: (v) {
                  setState(() => c.filterDirection = v ?? '');
                  c.loadLogs();
                },
              ),
              const SizedBox(width: 10),
              _dropdown(
                label: '报文阶段',
                value: c.filterEventType,
                items: const [
                  allItem,
                  DropdownMenuItem(value: 'discovery', child: Text('发现')),
                  DropdownMenuItem(value: 'pairing', child: Text('配对')),
                  DropdownMenuItem(value: 'sync', child: Text('同步')),
                  DropdownMenuItem(value: 'conflict', child: Text('冲突')),
                ],
                onChanged: (v) {
                  setState(() => c.filterEventType = v ?? '');
                  c.loadLogs();
                },
              ),
              const SizedBox(width: 10),
              _dropdown(
                label: '协议',
                value: c.filterProtocol,
                items: const [
                  allItem,
                  DropdownMenuItem(value: 'http', child: Text('HTTP')),
                  DropdownMenuItem(
                      value: 'udp_broadcast', child: Text('UDP 广播')),
                  DropdownMenuItem(value: 'mdns', child: Text('mDNS')),
                ],
                onChanged: (v) {
                  setState(() => c.filterProtocol = v ?? '');
                  c.loadLogs();
                },
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 10,
            children: [
              FilledButton.icon(
                onPressed: () => c.loadLogs(),
                icon: const Icon(Icons.refresh, size: 18),
                label: const Text('刷新'),
              ),
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: SyncColors.danger,
                  side: const BorderSide(color: SyncColors.danger),
                ),
                onPressed: _clearLogs,
                child: const Text('清空日志'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (c.logError != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text('报文加载失败：${c.logError}',
                  style: const TextStyle(
                      color: SyncColors.danger, fontSize: 13)),
            ),
          _LogsTable(
            logs: c.logPage.logs,
            discoveryRunning: c.status?.discoveryRunning ?? false,
          ),
        ],
      ),
    );
  }
}

class _LogsTable extends StatelessWidget {
  const _LogsTable({required this.logs, required this.discoveryRunning});

  final List<SyncLogEntry> logs;
  final bool discoveryRunning;

  Widget _statusBadge(String status) {
    final color = status == 'success'
        ? SyncColors.success
        : status == 'failed'
            ? SyncColors.danger
            : Colors.grey;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .2),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(status, style: TextStyle(fontSize: 11.5, color: color)),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (logs.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Center(
          child: Text(
            discoveryRunning
                ? '暂无报文记录（发现、配对、同步完成后会在这里显示）'
                : '局域网同步未开启 — 请在上方「设置」中开启并保存后，广播报文将在此显示',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13,
              color: discoveryRunning
                  ? Colors.white.withValues(alpha: .5)
                  : SyncColors.warning,
            ),
          ),
        ),
      );
    }

    const cell = TextStyle(fontSize: 12.5);
    const head = TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700);
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: SingleChildScrollView(
        child: DataTable(
          headingRowColor: WidgetStateProperty.all(SyncColors.field),
          dataRowMinHeight: 36,
          dataRowMaxHeight: 48,
          columnSpacing: 14,
          columns: const [
            DataColumn(label: Text('时间', style: head)),
            DataColumn(label: Text('方向', style: head)),
            DataColumn(label: Text('协议', style: head)),
            DataColumn(label: Text('对端', style: head)),
            DataColumn(label: Text('阶段', style: head)),
            DataColumn(label: Text('状态', style: head)),
            DataColumn(label: Text('消息', style: head)),
            DataColumn(label: Text('大小', style: head)),
          ],
          rows: [
            for (final log in logs)
              DataRow(cells: [
                DataCell(Text(formatDateTime(log.timestamp), style: cell)),
                DataCell(Text(directionLabel(log.direction), style: cell)),
                DataCell(Text(protocolLabel(log.protocol), style: cell)),
                DataCell(Text(
                    log.peerId?.isNotEmpty == true ? log.peerId! : '-',
                    style: cell)),
                DataCell(Text(eventLabel(log.eventType), style: cell)),
                DataCell(_statusBadge(log.status)),
                DataCell(ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 260),
                  child: Text(
                      log.message?.isNotEmpty == true ? log.message! : '-',
                      style: cell,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis),
                )),
                DataCell(Text(humanSize(log.dataSize), style: cell)),
              ]),
          ],
        ),
      ),
    );
  }
}
