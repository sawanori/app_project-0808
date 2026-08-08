package com.actionstarter.navigation

/**
 * 仕様§35の5画面に対応するNavigationルート定義。計画書§10.1準拠。
 *
 * `kotlinx.serialization`追加を避けるため型安全Navigation（Nav3のtype-safe destinations等）は
 * 使用せず、sealed interfaceによる文字列ルート方式とする（依存追加はスコープ外）。
 *
 * 本オブジェクトはルート文字列の宣言のみを持つ。[ActionStarterNavHost]への実際の結線
 * （各ルートとComposableの対応付け）はC5でintegration owner（domain-implementer）が実装済み。
 */
sealed interface Destinations {
    val route: String

    data object EventSelection : Destinations {
        override val route: String = "eventSelection"
    }

    data object PlanReview : Destinations {
        override val route: String = "planReview"
    }

    data object Execution : Destinations {
        override val route: String = "execution"
    }

    data object Departure : Destinations {
        override val route: String = "departure"
    }

    data object Recovery : Destinations {
        override val route: String = "recovery"
    }
}
