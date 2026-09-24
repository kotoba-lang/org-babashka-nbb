import { $APP, shadow$provide } from "./nbb_core.js";
const shadow_esm_import = function(x) { return import(x) };
$APP.w6=function(a,b){const c=Array.prototype.slice.call(arguments),d=c.shift();if(typeof d=="undefined")throw Error("[goog.string.format] Template required");return d.replace(/%([0\- \+]*)(\d+)?(\.(\d+))?([%sfdiu])/g,function(e,f,g,k,q,t,v,z){if(t=="%")return"%";const B=c.shift();if(typeof B=="undefined")throw Error("[goog.string.format] Not enough arguments");arguments[0]=B;return $APP.w6.$d[t].apply(null,arguments)})};/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
$APP.w6.$d={};$APP.w6.$d.s=function(a,b,c){return isNaN(c)||c==""||a.length>=Number(c)?a:a=b.indexOf("-",0)>-1?a+(0,$APP.bC)(" ",Number(c)-a.length):(0,$APP.bC)(" ",Number(c)-a.length)+a};
$APP.w6.$d.f=function(a,b,c,d,e){d=a.toString();isNaN(e)||e==""||(d=parseFloat(a).toFixed(e));let f;f=Number(a)<0?"-":b.indexOf("+")>=0?"+":b.indexOf(" ")>=0?" ":"";Number(a)>=0&&(d=f+d);if(isNaN(c)||d.length>=Number(c))return d;d=isNaN(e)?Math.abs(Number(a)).toString():Math.abs(Number(a)).toFixed(e);a=Number(c)-d.length-f.length;b.indexOf("-",0)>=0?d=f+d+(0,$APP.bC)(" ",a):(b=b.indexOf("0",0)>=0?"0":" ",d=f+(0,$APP.bC)(b,a)+d);return d};
$APP.w6.$d.d=function(a,b,c,d,e,f,g,k){return $APP.w6.$d.f(parseInt(a,10),b,c,d,0,f,g,k)};$APP.w6.$d.i=$APP.w6.$d.d;$APP.w6.$d.u=$APP.w6.$d.d;$APP.vY.j($APP.yK,null);$APP.kA(new $APP.G(null,2,[$APP.Gy,new $APP.G(null,1,[$APP.pH,{format:$APP.w6}],null),$APP.yt,new $APP.G(null,1,[$APP.pH,new $APP.G(null,1,[$APP.EM,$APP.w6],null)],null)],null));