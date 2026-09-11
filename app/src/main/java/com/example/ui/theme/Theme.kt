package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = Emerald500,
    secondary = Amber500,
    tertiary = Emerald400,
    background = SpaceBlack,
    surface = Zinc950,
    onPrimary = SpaceBlack,
    onSecondary = SpaceBlack,
    onTertiary = SpaceBlack,
    onBackground = Slate200,
    onSurface = Slate50,
    surfaceVariant = Zinc900,
    onSurfaceVariant = Slate400,
    outline = Zinc800
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Emerald600,
    secondary = Amber600,
    tertiary = Emerald500,
    background = LightCanvas,
    surface = LightSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

