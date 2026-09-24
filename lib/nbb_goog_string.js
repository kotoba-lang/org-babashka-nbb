import { $APP, shadow$provide } from "./nbb_core.js";
const shadow_esm_import = function(x) { return import(x) };
$APP.$5=function(a,b){const c=Array.prototype.slice.call(arguments),d=c.shift();if(typeof d=="undefined")throw Error("[goog.string.format] Template required");return d.replace(/%([0\- \+]*)(\d+)?(\.(\d+))?([%sfdiu])/g,function(e,f,g,m,q,t,w,A){if(t=="%")return"%";const C=c.shift();if(typeof C=="undefined")throw Error("[goog.string.format] Not enough arguments");arguments[0]=C;return $APP.$5.$d[t].apply(null,arguments)})};/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
$APP.$5.$d={};$APP.$5.$d.s=function(a,b,c){return isNaN(c)||c==""||a.length>=Number(c)?a:a=b.indexOf("-",0)>-1?a+(0,$APP.YD)(" ",Number(c)-a.length):(0,$APP.YD)(" ",Number(c)-a.length)+a};
$APP.$5.$d.f=function(a,b,c,d,e){d=a.toString();isNaN(e)||e==""||(d=parseFloat(a).toFixed(e));let f;f=Number(a)<0?"-":b.indexOf("+")>=0?"+":b.indexOf(" ")>=0?" ":"";Number(a)>=0&&(d=f+d);if(isNaN(c)||d.length>=Number(c))return d;d=isNaN(e)?Math.abs(Number(a)).toString():Math.abs(Number(a)).toFixed(e);a=Number(c)-d.length-f.length;b.indexOf("-",0)>=0?d=f+d+(0,$APP.YD)(" ",a):(b=b.indexOf("0",0)>=0?"0":" ",d=f+(0,$APP.YD)(b,a)+d);return d};
$APP.$5.$d.d=function(a,b,c,d,e,f,g,m){return $APP.$5.$d.f(parseInt(a,10),b,c,d,0,f,g,m)};$APP.$5.$d.i=$APP.$5.$d.d;$APP.$5.$d.u=$APP.$5.$d.d;$APP.EX.j($APP.cN,null);$APP.hC(new $APP.H(null,2,[$APP.Fy,new $APP.H(null,1,[$APP.RK,{format:$APP.$5}],null),$APP.vt,new $APP.H(null,1,[$APP.RK,new $APP.H(null,1,[$APP.eP,$APP.$5],null)],null)],null));