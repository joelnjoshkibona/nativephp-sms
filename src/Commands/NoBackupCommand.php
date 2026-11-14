<?php

namespace Blutrixx\NativephpSms\Commands;

use Illuminate\Console\Command;

/**
 * The vault lives only on this phone, and its file must never reach Android's backup or a device transfer: the plugin's
 * post_compile hook (nativephp.json) turns android:allowBackup off in the generated manifest and drops the backup rules
 * attributes. The plugin's assets also replace both rules files with "exclude everything", in case a build skips this.
 */
class NoBackupCommand extends Command
{
    protected $signature = 'sms:no-backup {--platform=} {--build-path=} {--plugin-path=} {--app-id=} {--config=} {--plugins=}';

    protected $description = 'Turn Android backup off for the SMS app (the vault must never leave the phone)';

    public function handle(): int
    {
        if ($this->option('platform') !== 'android') {
            return self::SUCCESS;
        }
        $manifest = rtrim((string) $this->option('build-path'), '/').'/app/src/main/AndroidManifest.xml';
        if (! is_file($manifest)) {
            $this->error("No manifest at {$manifest}");

            return self::FAILURE;
        }
        $xml = (string) file_get_contents($manifest);
        $changed = \Blutrixx\NativephpSms\NoBackupManifest::harden($xml);
        if ($changed !== $xml) {
            file_put_contents($manifest, $changed);
        }
        $this->line('Android backup is off (android:allowBackup="false").');

        return self::SUCCESS;
    }
}
