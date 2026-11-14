<?php

// Structural checks only: the Kotlin side can only be exercised on a device/emulator (see README, "Spike").

it('has a valid manifest whose bridge functions all exist in SmsFunctions.kt', function () {
    $manifest = json_decode(file_get_contents(__DIR__.'/../nativephp.json'), true);
    $kotlin = file_get_contents(__DIR__.'/../resources/android/SmsFunctions.kt');

    expect($manifest['bridge_functions'])->not->toBeEmpty();
    foreach ($manifest['bridge_functions'] as $fn) {
        $class = last(explode('.', $fn['android']));
        expect($kotlin)->toContain("class {$class}(");
    }
});

it('declares every component the SMS role requires', function () {
    $android = json_decode(file_get_contents(__DIR__.'/../nativephp.json'), true)['android'];
    $actions = [];
    foreach (['receivers', 'services', 'activities'] as $kind) {
        foreach ($android[$kind] as $component) {
            foreach ($component['intent_filters'] ?? [] as $filter) {
                foreach ((array) $filter['action'] as $action) {
                    $actions[] = $action;
                }
            }
        }
    }

    expect($actions)->toContain(
        'android.provider.Telephony.SMS_DELIVER',
        'android.provider.Telephony.WAP_PUSH_DELIVER',
        'android.intent.action.SENDTO',
        'android.intent.action.RESPOND_VIA_MESSAGE',
    );
});

it('gives every event a constructor parameter per native payload key', function () {
    $params = array_map(fn ($p) => $p->getName(), (new ReflectionClass(\Blutrixx\NativephpSms\Events\DefaultSmsResult::class))->getConstructor()->getParameters());

    expect($params)->toEqual(['granted', 'default_package', 'api', 'reason', 'id']);
});

it('turns Android backup off in the generated manifest', function () {
    $manifest = <<<'XML'
<manifest><application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:label="App"><activity android:name=".Main"/></application></manifest>
XML;
    $out = \Blutrixx\NativephpSms\NoBackupManifest::harden($manifest);
    expect($out)->toContain('android:allowBackup="false"')
        ->not->toContain('allowBackup="true"')
        ->not->toContain('fullBackupContent')
        ->not->toContain('dataExtractionRules')
        ->toContain('<activity android:name=".Main"/>');
    expect(\Blutrixx\NativephpSms\NoBackupManifest::harden('<manifest><application android:label="A"></application></manifest>'))
        ->toContain('<application android:allowBackup="false" android:label="A">');
    $plugin = json_decode(file_get_contents(__DIR__.'/../nativephp.json'), true);
    expect($plugin['hooks']['post_compile'])->toBe('sms:no-backup');
    foreach (array_keys($plugin['assets']['android']) as $source) {
        expect(file_exists(__DIR__.'/../resources/'.$source))->toBeTrue();
    }
});
