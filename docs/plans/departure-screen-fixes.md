# 出発画面（DepartureScreen）欠陥修正 実装計画書

> 対象仕様: `Action_Starter_Master_Specification_v2.0_Android.md`§29「Departure Mode」・§35 Screen4「Leave」・§95「Android固有のプラットフォーム制約とリスク」4節「権限一覧表」（本書では慣例に倣い「§95.4」と表記）
> 前提基盤: `DepartureViewModel.kt`／`DepartureScreen.kt`／`DepartureUiState.kt`（P3-C5 Green実装済み）・`ActionStarterNavHost.kt`のDeparture route配線・既存テスト群（T-DEP-*／T-DEPVM-*／T-P4DEP-*／T-PERM3-*／T-DEP2-*）
> 種別: 欠陥修正フェーズ（ユーザーが実機で発見した3件、オーケストレーターが実コードで根因診断済み。dev-workflow標準フロー: 計画→Red→Green）
> 承認状態: **アーキテクトレビュー合格（Pass 1指摘1件を反映済み、§3.2参照）。ユーザー承認済み（確認事項は§11既定で確定）。Green検収合格・コミット済み（C1=`aa1e1a6`欠陥①②〔ViewModel〕・C2=`58cf799`欠陥②③〔Screen/NavHost/strings、DepartureScreen.ktが欠陥②③両方の変更を含むためファイル単位で分割）。全819件Green(skip1)・lint 0/23（MissingTranslation含む）。ADR起票中。実機受け入れはオーケストレーターが実施予定。**

---

## §0. 結論ファースト

出発画面に3件の欠陥がある。**欠陥①**（移動時間手入力が無反応）は`onManualTravelMinutesChanged`が値を保存するだけで消費先が存在しないことが原因。**欠陥②**（権限カードの誤表示・無反応）は`recalculate`が行き先未解決時に早期returnし`permissionState`の反映をスキップすることが原因。**欠陥③**（戻れない）は`DepartureScreen`／NavHost双方に戻り導線が一切配線されていないことが原因。3件とも既存の`DepartureUiState`／`DepartureViewModel`の構造を壊さない末尾追加・条件分岐是正で修正可能であり、新規の外部依存・破壊的変更は不要と判断する。

手入力ETAの計算式は`estimatedArrival = now + manualMinutes`（現在時刻起点）と結論する。根拠は3点: (a) `travel_time_manual_label`の文言が"Travel time (minutes)"（所要時間）であり出発時刻・希望バッファではない、(b) 自動計算経路（`estimateAndApplyRoute`）が既に同じ形（`now.plus(estimate.duration)`）を採用しており手入力はこの代替値という位置づけが自然、(c) 仕様§95.4の`ACCESS_FINE_LOCATION`拒否時フォールバック行が「現在地起点の自動ETA計算を無効化し…Travel Timeの手動入力にフォールバック」と明記しており、手入力は自動ETA計算の代替そのものと規定している。

戻り先は`EventSelection`（`popUpTo(EventSelection){inclusive=true}`）と結論する。根拠: `ActionStarterNavHost.kt`を確認したところ、Departure routeへの遷移元は`ExecutionScreen`の`onNavigateToDeparture`（Execution完了時）**の1経路のみ**であり、これは`navigate(Destinations.Departure.route)`（`popUpTo`指定なし）のためExecutionはバックスタックに残存している。単純な`popBackStack()`は完了済みのExecution画面（アラーム・FGS停止済み・通知キャンセル済み）へ戻ってしまい不適切。`ExecutionScreen`が既に持つ`onNavigateToEventSelection`（プロセス再生成時の復元不能フォールバック）と同型の「バックスタックを`EventSelection`まで巻き戻す」パターンを踏襲するのが唯一の遷移元と整合する設計と判断する。

---

## §1. 目的・背景

3欠陥はいずれもP3-C5（Green実装）〜再デザインサイクル2の間に生じた**未完了の配線**であり、新規の回帰ではない。特に欠陥①は、既存テストT-DEP2-3自身のKDocが「本ケースのスコープは入力操作でonMinutesChangeが正しい値で呼ばれることに限定する（ETAの再表示自体はDepartureViewModel/DepartureScreen側の責務であり P3-C5で結線される）」と明記しており、**ETA再計算側の配線が当初から「後で行う」前提のまま置き去りにされていた**ことが実コード確認で判明した（`DepartureViewModel.kt:135`のKDoc「F28（S-2裁定）: 手動Travel Time入力値の反映のみを行う」という記述自体が、未完了の状態を恒久仕様であるかのように記録してしまっていた）。

---

## §2. 仕様整合（事前確認結果）

- **§29原文（全文）**: 「最新現在地・経路情報から再計算。」＋UIモックアップ（Estimated arrival／Event／Buffer／[Start navigation]）。手入力の計算式については言及なし。
- **§35 Screen4「Leave」原文（全文）**: 「Leave now／ETA／Event starts／Start navigation」のモックアップのみ。戻るボタンは描画されておらず、戻り先についての明示的規定もない（終端画面という設計意図と整合）。
- **§95.4「権限一覧表」`ACCESS_FINE_LOCATION`行（原文）**: 「取得タイミング: Departure Mode / Reality Check機能の初回利用時」「拒否時のフォールバック挙動: 現在地起点の自動ETA計算を無効化し、出発地の手動選択またはTravel Timeの手動入力にフォールバック。」——手入力が自動ETA計算の正式な代替経路であることの仕様上の根拠（§0参照）。
- **既存実装の確認（欠陥①）**: `DepartureViewModel.onManualTravelMinutesChanged`（L136-138）は`_uiState.value.copy(manualTravelMinutes = minutes)`のみ。`estimatedArrival`／`arrivalBuffer`を更新する呼び出し元はgrep（`estimatedArrival =`／`arrivalBuffer =`の代入箇所）で全件確認したが、`applyPlanBaseline`と`estimateAndApplyRoute`の2箇所のみで、手入力経路からの更新は存在しない。
- **既存実装の確認（欠陥②）**: `recalculate`（L156-190）は`locationName.isNullOrBlank()`が真の場合`markDestinationUnresolved()`を呼びreturnする（L158-161）。この分岐は`permissionState`を一切変更しない。一方、`locationName`が非空の場合は同関数内でL163の`hasAnyLocationPermission()`チェックにより`permissionState`が確定する。**この非対称性が欠陥②の直接の原因**——行き先の有無によって`permissionState`が反映されたりされなかったりする。
- **既存実装の確認（欠陥③）**: `DepartureScreen`（`fun DepartureScreen(uiState, onRequestLocationPermission, onOpenLocationSettings, onManualTravelMinutesChange, onTransportModeSelected)`）に`onNavigateBack`相当の引数は存在しない。`ActionStarterNavHost.kt:386-388`の`composable(Destinations.Departure.route) { DepartureRoute(vmFactory = vmFactory) }`、および`DepartureRoute`本体（L567-607）のいずれにも戻り導線の配線がない。`BackHandler`（`androidx.activity.compose.BackHandler`）は本アプリで現時点まで一度も使われていない（grep確認、新規採用となる）。

---

## §3. 機能一覧と仕様（欠陥ごとの根因・修正方針）

### 3.1 欠陥①: 手入力Travel TimeからのETA算出

**修正方針**: `onManualTravelMinutesChanged(minutes)`を拡張し、`manualTravelMinutes`の保存に加えて`estimatedArrival = now + minutes分`・`arrivalBuffer = eventStart - estimatedArrival`を算出し反映する（`now`は既存の`clock: Clock`フィールドを再利用、`eventStart`は`_uiState.value.eventStart`から取得）。`minutes == null`（空入力・不正入力）の場合は`estimatedArrival`／`arrivalBuffer`を`null`へ戻す（偽ETAを残さない、既存`estimateAndApplyRoute`の失敗時ハンドリングと同じ設計思想）。KDocの「F28: 反映のみを行う」という記述を実際の挙動に合わせて是正する。

**変更ファイル**: `DepartureViewModel.kt`（`onManualTravelMinutesChanged`拡張）。`DepartureUiState.kt`・`DepartureScreen.kt`は変更不要（既存フィールド・描画ロジックがそのまま新しい値を表示する）。

### 3.2 欠陥②: 権限カードの誤表示・ボタン無反応

**修正方針（アーキテクトレビューPass 1指摘反映後）**: 当初案「`recalculate`内の権限チェックを`locationName`の空判定より前に移動する」だけでは不完全であることが判明した。`DepartureScreen.kt`の実コード確認（L212: `LocationPermissionRationaleCard`は`permissionState == NOT_REQUESTED`のみで表示、L260: 設定を開くボタンは`permissionState == DENIED`のみで表示）により、権限判定順序を是正しても**「権限拒否＋行き先未解決」の組み合わせではDENIED設定カードが残留し的外れな誘導になる**ことが分かった（位置権限の設定を開いても行き先未解決という真の問題は解決しない）。

修正は2段構えとする:
1. `DepartureViewModel.recalculate`内の権限チェック（`hasAnyLocationPermission()`）を`locationName`の空判定より前に移動し、行き先の解決可否に関わらず`permissionState`を常に確定させる（GRANTED／DENIED）。権限判定自体は同期関数（`permissionGate.isGranted`）であり、システム権限ダイアログを起動しない純粋なチェックのため、この並べ替えは「§95.4: 初回利用時に要求」という取得タイミング規定と衝突しない（実際にダイアログを開くのは`onRequestLocationPermission`経由の`ActivityResultLauncher`のみで、本修正はそこに触れない）。
2. `DepartureScreen.kt`の両権限カード（L212のRationaleカード・L260の設定を開くボタン）の表示条件へ`&& !uiState.isDestinationUnresolved`を追加する。行き先未解決時は権限状態（GRANTED／DENIED）に関わらずどちらのカードも一切表示せず、手入力導線（`TravelTimeInput`、`showManualFallback`が`isDestinationUnresolved`を含む既存条件のため引き続き表示される）のみを見せる。

**変更ファイル**: `DepartureViewModel.kt`（`recalculate`内の判定順序の是正）・`DepartureScreen.kt`（両権限カードの表示条件へ`&& !isDestinationUnresolved`追加）。

### 3.3 欠陥③: 戻れない

**修正方針**: `DepartureScreen`へ`onNavigateBack: () -> Unit = {}`（既定値付き、既存の`DepartureScreen(uiState = uiState)`単一引数呼び出しを壊さない）を追加し、他画面（`SettingsScreen`の`settings_back_button`）と同型の戻るボタンを描画する（testTag: `departure_back_button`、文言は新規文字列キー`departure_back_button_label`）。`ActionStarterNavHost.kt`の`DepartureRoute`内で、明示的な戻るボタンとシステムBack（`BackHandler`）の両方を同一の遷移——`navController.navigate(Destinations.EventSelection.route) { popUpTo(Destinations.EventSelection.route) { inclusive = true } }`（`ExecutionScreen.onNavigateToEventSelection`と同型）——へ配線する。両方を同じ遷移に揃えるのは、ボタンとシステムBackの挙動が食い違う（片方は安全に戻り、片方は完了済みExecutionへ戻ってしまう）事態を避けるため。「ナビを開始」ボタン（`isStartNavigationEnabled`固定false）は無改修のまま維持する。

**変更ファイル**: `DepartureScreen.kt`（`onNavigateBack`引数・戻るボタン描画）・`ActionStarterNavHost.kt`（`DepartureRoute`への`onNavigateBack`実装配線・`BackHandler`追加）・`values(-ja)/strings.xml`（新規1キー）。

---

## §4. テストケースリスト（Robolectric JVM、既存件数無傷が前提）

| ID | 分類 | 内容 | Red対象 |
|---|---|---|---|
| T-DEPFIX-1 | 正常 | 手入力Travel Time（例: 25分）→ `estimatedArrival` = `now + 25分`が反映される | 新規（`DepartureViewModelTest`） |
| T-DEPFIX-2 | 正常 | 手入力Travel Time反映時、`arrivalBuffer` = `eventStart - estimatedArrival`が連動して更新される | 新規 |
| T-DEPFIX-3 | エッジケース | 手入力を空/不正値へ戻す（`minutes = null`）と`estimatedArrival`／`arrivalBuffer`が`null`へ戻る（偽ETA防止） | 新規 |
| T-DEPFIX-4 | 正常 | 行き先未解決（`locationName`空）かつ位置権限が実際には許可済みの場合、`recalculate`後に`permissionState == GRANTED`になる（許可カードが出ない条件） | 新規 |
| T-DEPFIX-5 | 異常 | 行き先未解決かつ権限拒否の場合、`permissionState == DENIED`かつ`isDestinationUnresolved == true`の両方が同時に成立する | 新規 |
| T-DEPFIX-6a | UI（アーキテクトレビューPass 1・確認/回帰ガード） | `permissionState == GRANTED`かつ`isDestinationUnresolved == true`のとき、権限カード（Rationale・設定を開く）がいずれも表示されず`TravelTimeInput`のみ表示される | 新規（`DepartureRoutingScreenTest`、直接`DepartureUiState`構成。現行コードでも既にGRANTED時はどちらのカードも条件を満たさないため確認用途、Green化はDefect②のViewModel側修正がGRANTEDへ到達させて初めて意味を持つ） |
| T-DEPFIX-6b | UI（アーキテクトレビューPass 1指摘・Red対象） | `permissionState == DENIED`かつ`isDestinationUnresolved == true`のとき、権限カード（Rationale・設定を開くの両方）が表示されず`TravelTimeInput`のみ表示される（的外れな設定導線の解消） | 新規（`DepartureRoutingScreenTest`、直接`DepartureUiState`構成。現行の設定を開くボタン条件は`permissionState == DENIED`のみのため、この組み合わせでは現在誤って表示される＝真にRed） |
| T-DEPFIX-7 | 正常 | `DepartureScreen`の戻るボタンタップで`onNavigateBack`が1回呼ばれる | 新規 |
| T-DEPFIX-8 | 正常 | `ActionStarterNavHost`のDeparture route配線で、戻るボタン操作が`EventSelection`（`popUpTo inclusive`）へ遷移する（Execution画面が再表示されないことの回帰ガード） | 新規（Navigation系テスト） |
| T-DEPFIX-9 | 回帰 | 既存T-DEP-1〜4／T-DEPVM-1〜9／T-P4DEP-1〜5／T-PERM3-1〜5／T-DEP2-1〜6が無傷でGreenを維持する | 既存（回帰ガード、変更なし） |

---

## §5. エラー＆レスキューマップ

| 処理 | 想定される異常 | ハンドリング方法 | ユーザーへの影響 |
|---|---|---|---|
| 手入力Travel Time反映 | 数字以外・空文字の入力 | `TravelTimeInput`が既に`onMinutesChange(null)`として通知する既存契約を維持。ViewModel側は`null`受信時に`estimatedArrival`／`arrivalBuffer`を`null`へ戻し偽ETAを残さない | 「移動時間未取得」表示に戻る（既存の`departure_eta_unavailable_message`を再利用、新規文言不要） |
| 手入力Travel Time反映 | 極端に大きい値（例: 9999分）の入力 | 上限バリデーションは行わない（既存`estimateAndApplyRoute`もRouting APIの返す実測値に上限チェックを課していないため、手入力のみ特別扱いしない設計方針を踏襲。ユーザー確認事項1で確定） | `arrivalBuffer`が大きな負値になりうるが、既存の負値表示（`departure_buffer_negative_warning`）がそのまま警告として機能する |
| 手入力Travel Time反映 | 負のTravel Time値によるETAの逆行 | **実コード確認済み（`TravelTimeInput.kt`）**: `onValueChange`は`raw.filter { it.isDigit() }.toIntOrNull()`で構成されており、`Char.isDigit()`は`-`（マイナス記号）を含まないため`digitsOnly`に符号が残ることは構造的にない。したがって`manualTravelMinutes`は非負`Int`または`null`のいずれかしか取り得ず、`coerceAtLeast(0)`によるクランプは不要と判断する（ユーザー確認事項1で確定） | 該当なし（構造的に発生しない） |
| `recalculate`の権限判定順序変更＋カード表示条件変更 | 行き先未解決かつ権限拒否が同時に真になるケース（アーキテクトレビューPass 1指摘） | 両権限カードの表示条件へ`&& !isDestinationUnresolved`を追加済みのため、`isDestinationUnresolved == true`の間はRationale・設定を開くのいずれのカードも表示しない。`showManualFallback`は`isDestinationUnresolved`を含む既存ORのため手入力導線は変わらず表示される | 想定どおりの表示（権限カードなし、手入力導線のみ表示、的外れな設定導線への誘導なし） |
| 戻るボタン／システムBack | 遷移中の多重タップ（NavHostへの多重`navigate`呼び出し） | 既存の`EventSelection`遷移（`onNavigateToEventSelection`）と同じ`popUpTo(inclusive=true)`パターンをそのまま再利用するため、既存で許容されているのと同じリスク水準に留まる（新規の防御は追加しない） | 最悪でも同一画面への多重遷移試行のみ、クラッシュしない |
| 戻るボタン／システムBack | `BackHandler`未対応の古いCompose Navigationバージョンとの非互換 | 依存バージョンは`androidx.activity:activity-compose`（既存依存、新規追加不要）に含まれる安定APIのため非互換リスクは低いが、Step 3着手時にContext7で現行バージョンでの挙動を確認する | 該当なし（事前確認で担保） |

---

## §6. 変更対象ファイル構成

- **変更**: `app/src/main/java/com/actionstarter/features/departure/DepartureViewModel.kt`（欠陥①②）・`app/src/main/java/com/actionstarter/features/departure/DepartureScreen.kt`（欠陥③、`onNavigateBack`引数・戻るボタン）・`app/src/main/java/com/actionstarter/navigation/ActionStarterNavHost.kt`（欠陥③、`DepartureRoute`への遷移配線・`BackHandler`）・`app/src/main/res/values/strings.xml`／`values-ja/strings.xml`（新規1キー`departure_back_button_label`）。
- **既存無改修**: `DepartureUiState.kt`（新規フィールド不要、既存フィールドの意味づけのみ拡張）・`TravelTimeInput.kt`（呼び出し元の消費ロジックのみ変更、Composable自体は無改修）・Routes API／geocode／位置サービス各実装（スコープ外、§0・本節参照）。

---

## §7. 依存関係・技術選定の根拠

- **新規外部依存なし**。`BackHandler`は`androidx.activity.compose`（既存の`activity-compose`依存に含まれる、`AppContainer`・`ActionStarterNavHost`が既に同アーティファクトの`rememberLauncherForActivityResult`等を使用済み）の標準API。本アプリでの初採用となるためStep 3着手時にContext7で用法を確認する（§5参照）。
- **`now + manualMinutes`採用根拠**: §0・§2で述べたとおり、UI文言・既存の自動計算経路・仕様§95.4の3点がいずれも「手入力は自動ETA計算の代替」という同一の結論を支持する。
- **戻り先`EventSelection`採用根拠**: Departure routeへの遷移元がExecution完了の1経路のみであることをNavHost実コードで確認済み（複数の遷移元候補を想定した設計は不要、過剰設計を避ける）。

---

## §8. コミット粒度（確定、ユーザー承認済み）

- **C1**: 欠陥①＋②。`DepartureViewModel.kt`（`onManualTravelMinutesChanged`拡張・`recalculate`の判定順序是正）に加え、アーキテクトレビューPass 1反映（§3.2）により`DepartureScreen.kt`の両権限カード表示条件（`&& !isDestinationUnresolved`追加）も本コミットへ含める——`DepartureScreen.kt`の変更点自体は欠陥②の完全化そのものであり欠陥③（戻り導線）とは無関係のため、ファイルが同じでも関心事は分離されたまま成立する。
- **C2**: 欠陥③（`DepartureScreen.kt`の`onNavigateBack`引数・戻るボタン描画〔C1の権限カード条件とは別箇所〕・`ActionStarterNavHost.kt`・strings、UI・ナビゲーション配線として独立）

理由: ①②は同一関数群（`recalculate`／`onManualTravelMinutesChanged`）の状態設定漏れという同じ性質の欠陥であり1コミットにまとめる方がレビュー単位として自然。③は画面遷移という別関心事のため分離する。

---

## §9. ADR起票方針（確定、ユーザー承認済み）

**起票する（1本）**。3設計判断（手入力ETA計算式=`now + manualMinutes`／戻り先=`EventSelection`／権限判定順序の是正＋カード表示条件）はいずれも非自明で将来の根拠として記録する価値があるため、軽微な欠陥修正ではあるが正式起票する。起票直前に`grep -n "^### ADR-" DECISIONS.md | tail -3`を再実行し最新確定ADRを確認したうえで、C2完了後に正式起票する。

---

## §10. 実機受け入れ手順

(a) 行き先未解決イベント（会議室名等）で位置権限が許可済みの端末にて、許可カードが表示されないことを確認する。(b) 同条件で手入力Travel Time欄に値を入力し、Estimated arrivalが即座に反映されることを確認する。(c) DepartureScreenで戻るボタンおよびシステムBackジェスチャーの両方が`EventSelection`へ遷移し、完了済みのExecution画面が再表示されないことを確認する。(d) 既存のPlan/Execution/Recoveryフローが無傷であることを確認する。

---

## §11. ユーザー確認事項（確定済み、ユーザー承認「推奨どおり進めて」により下記既定で確定・再確認不要）

1. **ADR起票要否**（§9）: **する**（1本、C2完了後）。3設計判断（手入力ETA計算式・戻り先設計・権限判定順序＋カード表示条件）は非自明で将来の根拠として残す価値があるため。
2. **手入力ETAの上限バリデーション**（§5）: **設けない**（既存Routing API実測値と同じ扱い）。ただし負値のみ防ぐ必要がないか実コードで確認した結果、`TravelTimeInput`の数値パース（`filter { it.isDigit() }`）が構造的に負値を生成しないことを確認したため、追加のクランプ処理も不要と判断した。
3. **コミット粒度**（§8）: **提案どおり2コミット**（C1=欠陥①②・C2=欠陥③）で確定。
