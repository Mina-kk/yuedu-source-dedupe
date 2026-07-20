package com.mina.yuedu.ui;
import android.content.*;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;
public final class AppleStyles {
 public static final int BG=0xfff5f5f7,CARD=0xffffffff,TEXT=0xff1d1d1f,MUTED=0xff6e6e73,LINE=0xffe5e5ea,BLUE=0xff007aff,GREEN=0xff34c759,ORANGE=0xffff9500,RED=0xffff3b30;
 private AppleStyles(){} public static int dp(Context c,int v){return(int)(v*c.getResources().getDisplayMetrics().density+.5f);} public static GradientDrawable round(int color,int radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;} public static GradientDrawable bordered(int color,int stroke,Context c){GradientDrawable d=round(color,12,c);d.setStroke(dp(c,1),stroke);return d;}
 public static TextView text(Context c,String s,float sp,int color){TextView v=new TextView(c);v.setText(s);v.setTextSize(sp);v.setTextColor(color);return v;}
 public static Button button(Context c,String s,boolean primary){Button b=new Button(c);b.setText(s);b.setTextSize(16);b.setTextColor(primary?Color.WHITE:BLUE);b.setAllCaps(false);b.setMinHeight(dp(c,48));b.setBackground(primary?round(BLUE,12,c):bordered(CARD,LINE,c));return b;}
 public static LinearLayout card(Context c){LinearLayout x=new LinearLayout(c);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));x.setBackground(round(CARD,16,c));x.setElevation(dp(c,1));return x;}
}
