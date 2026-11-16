# Защита JavaScript-интерфейса от вырезания оптимизатором R8 / ProGuard
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Сохранение моделей данных
-keepclassmembers class by.brest103.ssmpplus.** { *; }
