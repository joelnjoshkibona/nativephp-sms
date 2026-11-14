<?php

namespace Blutrixx\NativephpSms;

/** Android backup off in a generated AndroidManifest.xml (the sms:no-backup build hook). Plain PHP, so it is tested alone. */
final class NoBackupManifest
{
    /** The manifest with allowBackup false on <application> and no backup rules attributes. */
    public static function harden(string $xml): string
    {
        return (string) preg_replace_callback('/<application\b[^>]*>/s', function (array $m) {
            $tag = $m[0];
            $tag = (string) preg_replace('/\s+android:(fullBackupContent|dataExtractionRules)="[^"]*"/', '', $tag);
            if (preg_match('/android:allowBackup="[^"]*"/', $tag)) {
                return (string) preg_replace('/android:allowBackup="[^"]*"/', 'android:allowBackup="false"', $tag);
            }

            return (string) preg_replace('/<application\b/', '<application android:allowBackup="false"', $tag, 1);
        }, $xml, 1);
    }
}
