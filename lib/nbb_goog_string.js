import { $APP, shadow$provide } from "./nbb_core.js";
const shadow_esm_import = function(x) { return import(x) };
$APP.$5=function(a,b){const c=Array.prototype.slice.call(arguments),d=c.shift();if(typeof d=="undefined")throw Error("[goog.string.format] Template required");return d.replace(/%([0\- \+]*)(\d+)?(\.(\d+))?([%sfdiu])/g,function(e,f,g,m,q,t,w,B){if(t=="%")return"%";const C=c.shift();if(typeof C=="undefined")throw Error("[goog.string.format] Not enough arguments");arguments[0]=C;return $APP.$5.fe[t].apply(null,arguments)})};/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
$APP.$5.fe={};$APP.$5.fe.s=function(a,b,c){return isNaN(c)||c==""||a.length>=Number(c)?a:a=b.indexOf("-",0)>-1?a+(0,$APP.zF)(" ",Number(c)-a.length):(0,$APP.zF)(" ",Number(c)-a.length)+a};
$APP.$5.fe.f=function(a,b,c,d,e){d=a.toString();isNaN(e)||e==""||(d=parseFloat(a).toFixed(e));let f;f=Number(a)<0?"-":b.indexOf("+")>=0?"+":b.indexOf(" ")>=0?" ":"";Number(a)>=0&&(d=f+d);if(isNaN(c)||d.length>=Number(c))return d;d=isNaN(e)?Math.abs(Number(a)).toString():Math.abs(Number(a)).toFixed(e);a=Number(c)-d.length-f.length;b.indexOf("-",0)>=0?d=f+d+(0,$APP.zF)(" ",a):(b=b.indexOf("0",0)>=0?"0":" ",d=f+(0,$APP.zF)(b,a)+d);return d};
$APP.$5.fe.d=function(a,b,c,d,e,f,g,m){return $APP.$5.fe.f(parseInt(a,10),b,c,d,0,f,g,m)};$APP.$5.fe.i=$APP.$5.fe.d;$APP.$5.fe.u=$APP.$5.fe.d;$APP.fX.j($APP.NN,null);$APP.sD(new $APP.G(null,2,[$APP.Gy,new $APP.G(null,1,[$APP.AK,{format:$APP.$5}],null),$APP.wt,new $APP.G(null,1,[$APP.AK,new $APP.G(null,1,[$APP.XP,$APP.$5],null)],null)],null));