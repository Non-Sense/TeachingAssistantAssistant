# Moodleからダウンロードしたコードファイル群を自動でコンパイルして実行してテストケースをぶんまわして結果のcsvを吐く君

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
出力先ディレクトリには適当なディレクトリを指定する。処理が終わるとcsvが2件出力される。名前はコンフィグで変更可能。
3. 待つ
4. できたcsvをみる
5. Excelなどで見た目をいい感じにする

### 実例
`java -jar .\TeachingAssistantAssistant-1.0-SNAPSHOT-all.jar "I:\TA\01\TB8454_プログラミ-第1回 演習課題-16777" "I:\TA\01\testconfig.yaml" "I:\TA\01" -s`  
オプション`-s`を指定すると出力ファイルがShift_JISになるのでWindows環境などで文字化けしません。

***  

## 動作
ソースコードはUTF-8とShift_JISでコンパイルされ、文字化けエラーが発生しなかった方が採用される。  
ソースコードを全体を走査し、コンフィグファイルに指定する検索ワードにヒットした場合にどの課題のコードであるかを確定させる。  
コンパイルと実行はjvmからosコマンドを発行し実行する。(パスが通っていないとおそらく実行不可能)  

出力ファイルのテスト結果の凡例(AtCoder方式)
- ``AC`` Accepted
- ``WA`` WrongAnswer
- ``RE`` RuntimeError
- ``CE`` CompileError  

タイムアウトは実装めんどくさかったのでおそらくWAになります。  

### **注意**
ACとWAの判定は正規表現に依存するので詳細出力ファイルにて十分に結果を確認してください。誤判定がありえます。

***  

## コンフィグファイル
``` yaml
compileTimeout: 3000                # コンパイルのタイムアウト時間(ミリ秒)
runningTimeout: 5000                # 実行時間のタイムアウト(ミリ秒)
detailFileName: detail.csv          # 結果詳細csvのファイル名
summaryFileName: summary.csv        # 結果概要csvのファイル名
tasks:
  課題1:                            # 課題名を指定する
    word:                           # ソースファイルの"どの課題用かコメント"の検索用ワード
      - 課題1
      - 課題１
      - kadai1
      - Kadai1
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