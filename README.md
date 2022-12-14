# Moodleからダウンロードしたコードファイル群を自動でコンパイルして実行してテストケースをぶんまわして結果の~~csv~~エクセルファイルを吐く君

***
## 概要
概ねタイトルの通り。  
採点アシスタント。

***

## 使い方  

### 前提
- javacコマンドが実行可能 (パスが通っていること)
- javaコマンドが実行可能 (同上)

### 手順
1. ターミナルを開く(PowerShellなど)
2. `java -jar TeachingAssistantAssistant-1.0-SNAPSHOT-all.jar <ルートディレクトリ> <コンフィグファイル> <出力先ディレクトリ>`  
ルートディレクトリはMoodleからダウンロードしてきたZipを展開したものを指定する。  
   (`<学籍番号> <名前>_\d+_assignsubmission_file_`みたいなディレクトリが並んでいるところ。)  
コンフィグファイルはyamlファイルを指定する。(記法後述)  
出力先ディレクトリには適当なディレクトリを指定する。処理が終わると結果ファイルが出力される。名前はコンフィグで変更可能。
3. 待つ  
4. ~~できたcsvをみる~~
5. ~~Excelなどで見た目をいい感じにする~~
6. 眺める

### 実例
`java -jar .\TeachingAssistantAssistant-1.0-SNAPSHOT-all.jar "I:\TA\01\TB8454_プログラミ-第1回 演習課題-16777" "I:\TA\01\testconfig.yaml" "I:\TA\01"`

### 指定可能なオプション

| オプション | 説明 |
| :---: | ---- |
| `-l`  | 実行する最終ステップの指定。0=zip展開まで, 1=コンパイルまで, 2=実行&テストまで(デフォルト)  |
| `-t`  | テストを並列実行しないようにする。|
| `-m`  | 手動で判定する。 |
| `-c`  | 結果をcsvで出力する。(デフォルトはxlsx) |
| `-s`  | csvの結果ファイルをShift_JISで出力する。(xlsx出力には影響なし) |

`-m`オプションを指定する場合には`-t`オプションも**同時に指定**してください。

***  

## 動作
ソースコードはUTF-8とShift_JISでコンパイルされ、文字化けエラーが発生しなかった方が採用される。  
ソースコードを全体を走査し、コンフィグファイルに指定する検索ワードにヒットした場合にどの課題のコードであるかを確定させる。  
コンパイルと実行はjvmからosコマンドを発行し実行する。(パスが通っていないとおそらく実行不可能)  

展開/コンパイルの結果は指定したルートディレクトリ直下のworkspaceに吐き出される。

出力ファイルのテスト結果の凡例(AtCoder方式)  

| stat  |                     |      |
| :---: | ------------------- | ---- |
| AC    | Accepted            | 正答 |
| WA    | Wrong Answer        | 誤答 |
| TLE   | Time Limit Exceeded | 実行時間制限内に終了しなかった |
| RE    | Runtime Error       | 実行時エラー |
| CE    | Compile Error       | コンパイルエラー |
| IE    | Internal Error      | システム内部エラー |
| NF    | Not Found           | 提出がない。提出ソースコードから該当課題を特定できなかった。 |
| CF    | Conflict            | 課題に対するソースコードの提出が複数あり、結果を特定できなかった。<br>(Summaryにのみ出現) |


## 手動判定
`-t -m`オプションを指定して実行してください。  
コンパイルに成功しテストケースが1件以上あるコードに対して、テストケースが1件ずつ実行されます。  

テスト実行中の出力の例
```
running: t100d400:HomeWork1  課題1:natural1
expected:
45
stdout:
45
judge?: 
```
- `expected:` コンフィグファイルで記述した想定解
- `stderr:` 実行したプログラムの標準エラー出力
- `stdout:` 実行したプログラムの標準出力  

expected, stderr, stdoutはそれぞれ空で無いときに表示されます。  
`judge?: ` と表示されたら判定を入力してください。  
#### 許容される入力
- `AC` : `AC`, `A`, `ac`, `a`
- `WA` : `WA`, `W`, `wa`, `w`
- `RE` : `RE`, `R`, `re`, `r`
- `CE` : `CE`, `C`, `ce`, `c`

### **注意**
手動判定でない場合、ACとWAの判定は正規表現に依存するので詳細出力ファイルにて十分に結果を確認してください。誤判定がありえます。

***  

## コンフィグファイル

#### 課題特定用の検索ワードについて  
コンフィグに指定した`word`の文字列を、ソースファイル内で全文検索して一致した場合にそのソースファイルの課題を決定します。  
また、`excludeWord`で指定した文字列が一致した場合は、`word`で指定した文字列が一致していたとしても課題の決定はされません。  



``` yaml
compileTimeout: 3000                # コンパイルのタイムアウト時間(ミリ秒)
runningTimeout: 5000                # 実行時間のタイムアウト(ミリ秒)
outputFileName: output              # 出力するファイル名(拡張子は自動で付与されます)
tasks:
  課題1:                            # 課題名を指定する
    word:                           # ソースファイルの"どの課題用かコメント"の検索用ワード
      - 課題1
      - 課題１
      - kadai1
      - Kadai1
    excludeWord:                    # 除外ワード
      - "public class IntList"
      - "前回課題"
    testcase:
      - name: test1                          # テストケース名
        arg: "1 10"                          # コマンドライン引数(省略可能)
        input:                               # 標準入力(省略可能)
          - "1 2 3 4"                           # 1行目
          - "5 5"                               # 2行目
        expect:
          - "^.*(?<![-\\d])45(?!\\d).*"      # 想定標準出力(正規表現で指定)
      - name: test2
        arg: "5 30"
        expect:
          - "^.*(?<![-\\d])425(?!\\d).*"
  課題2:
    word:
      - 課題2
      - 課題２
      - kadai2
      - Kadai2
    testcase:
      - name: sample
        arg: "1 4 2"
        expect:
          - "1\\s*\\+\\s*4\\s*\\+\\s*2\\s*=\\s*7.*"
      - name: sample-reversed
        arg: "2 4 1"
        expect:
          - "2\\s*\\+\\s*4\\s*\\+\\s*1\\s*=\\s*7.*"
```

## 謝辞
zipの解凍用コードをお借りしました(CC0)。
[Kotlin で zip を安全に圧縮・展開する](https://qiita.com/jim/items/2c0b0a0acacd78f49b49)